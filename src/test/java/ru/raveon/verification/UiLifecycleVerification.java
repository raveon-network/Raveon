package ru.raveon.verification;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import org.bukkit.*;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;
import ru.raveon.Raveon;
import ru.raveon.api.menu.*;
import ru.raveon.api.menu.impl.AsyncMenu;
import ru.raveon.api.models.monitor.*;
import ru.raveon.config.MainConfigManager;
import ru.raveon.config.anticheat.HologramConfigManager;
import ru.raveon.listeners.bukkit.MenuListener;
import ru.raveon.manager.analytic.MonitorManager;
import ru.raveon.manager.analytic.hologram.HologramManager;
import ru.raveon.menu.players.PlayersMenuSettings;
import ru.raveon.menu.history.HistoryMenuSettings;
import sun.misc.Unsafe;

public final class UiLifecycleVerification {
    static final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    static final Map<UUID,Player> online = new HashMap<>();
    static boolean executing;
    static Player viewer;
    static Player target;
    static View view;
    static int opens;
    static int locations;
    static int assertions;
    static TestPlugin plugin;
    static Unsafe unsafe;

    static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    static Object get(Object owner, String name) throws Exception {
        return field(owner.getClass(),name).get(owner);
    }

    static void set(Object owner, String name, Object value) throws Exception {
        field(owner.getClass(),name).set(owner,value);
    }

    static void drain() {
        int count = 0;
        while (!tasks.isEmpty()) {
            if (++count > 100) throw new AssertionError("Unbounded scheduler");
            executing = true;
            try { tasks.remove().run(); } finally { executing = false; }
        }
    }

    static Inventory inventory(InventoryHolder holder, int size) {
        return HologramPacketVerification.proxy(Inventory.class, (p,m,a) -> {
            if (m.getName().equals("getHolder")) return holder;
            if (m.getName().equals("getSize")) return size;
            if (m.getName().equals("getType")) return InventoryType.CHEST;
            return HologramPacketVerification.defaultValue(m.getReturnType());
        });
    }

    static Player player(String name) {
        UUID id = UUID.randomUUID();
        World world = HologramPacketVerification.proxy(World.class, (p,m,a) -> {
            if (m.getName().equals("getUID")) return new UUID(0,1);
            return HologramPacketVerification.defaultValue(m.getReturnType());
        });
        Player player = HologramPacketVerification.proxy(Player.class, (p,m,a) -> {
            return switch (m.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "isOnline", "isValid" -> online.containsKey(id);
                case "getOpenInventory" -> view;
                case "openInventory" -> {
                    check(executing,"Opening on scheduler");
                    opens++;
                    view.top = (Inventory)a[0];
                    yield view;
                }
                case "getLocation" -> {
                    check(executing,"Position read only on scheduler");
                    locations++;
                    yield new Location(world,0,64,0);
                }
                case "getPassengers" -> List.of();
                case "getEntityId" -> 200;
                case "sendMessage", "sendActionBar" -> {
                    check(executing, "Message sent on scheduler");
                    yield null;
                }
                default -> HologramPacketVerification.defaultValue(m.getReturnType());
            };
        });
        online.put(id,player);
        return player;
    }

    static final class View extends InventoryView {
        Inventory top;
        final Inventory bottom = inventory(null,36);
        View(Inventory top) { this.top=top; }
        public Inventory getTopInventory(){return top;}
        public Inventory getBottomInventory(){return bottom;}
        public HumanEntity getPlayer(){return viewer;}
        public InventoryType getType(){return InventoryType.CHEST;}
        public String getTitle(){return "test";}
        public String getOriginalTitle(){return "test";}
        public void setTitle(String title){}
    }

    static final class TestMenu extends AsyncMenu<String> {
        CompletableFuture<String> future = new CompletableFuture<>();
        int loads;
        int applies;
        int creates;
        int clicks;
        boolean callbackCancelled;
        public String getTitle(){return "test";}
        public int getSize(){return 9;}
        public void getItemsAndButtons(){}
        protected CompletableFuture<String> loadData(){loads++;return future;}
        protected boolean applyData(String data, Player player){
            check(executing,"Apply on scheduler");applies++;return true;
        }
        protected Inventory createInventory(){
            check(executing,"Inventory constructed on scheduler");creates++;return inventory(this,9);
        }
        protected void populateInventory(){check(executing,"Items built on scheduler");}
        public void onClick(InventoryClickEvent event){
            clicks++;
            callbackCancelled=event.isCancelled();
            super.onClick(event);
        }
    }

    static class TestPlugin extends Raveon {
        MainConfigManager main;
        HologramConfigManager hologram;
        ru.raveon.manager.alert.ViolationManager violation;
        ru.raveon.service.PlayerOnlineService onlineService;
        public ru.raveon.manager.alert.ViolationManager getViolationManager(){return violation;}
        public ru.raveon.service.PlayerOnlineService getPlayerOnlineService(){return onlineService;}
        public MainConfigManager getMainConfigManager(){return main;}
        public HologramConfigManager getHologramConfigManager(){return hologram;}
        public Logger getLogger(){return Logger.getLogger("test");}
    }

    static void rejection(Runnable runnable,String label) {
        boolean rejected=false;
        try {runnable.run();} catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,label);
    }

    static void configurationTests() {
        for (int size : new int[]{-1,0,8,10,55,Integer.MAX_VALUE}) {
            MemoryConfiguration config = new MemoryConfiguration();
            config.set("size",size);
            rejection(() -> MenuSettings.fromSection(config),"Reject invalid size " + size);
        }
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("size",54);
        check(MenuSettings.fromSection(config).getSize()==54,"Valid chest size");
        rejection(() -> MenuSettings.validateSlots(List.of(-1),54),"Negative slot");
        rejection(() -> MenuSettings.validateSlots(List.of(54),54),"Slot beyond inventory");
        rejection(() -> MenuSettings.validateSlots(List.of(1,1),54),"Duplicate slot");
        config.set("decoration.INVALID_BLOCK",List.of(1));
        rejection(() -> MenuSettings.fromSection(config),"Invalid decor material");
        MemoryConfiguration players = new MemoryConfiguration();
        players.set("size",9);
        players.set("settings.player_slots",List.of(0,1));
        players.set("settings.check_lines",Integer.MAX_VALUE);
        players.set("settings.probs_per_line",Integer.MAX_VALUE);
        rejection(() -> PlayersMenuSettings.fromSection(players, plugin.getLanguageService()),"Overflow checks limit");
        players.set("settings.check_lines",2);
        players.set("settings.probs_per_line",5);
        players.set("settings.probability_multiplier",Double.NaN);
        rejection(() -> PlayersMenuSettings.fromSection(players, plugin.getLanguageService()),"NaN probability multiplier");
        MemoryConfiguration history = new MemoryConfiguration();
        history.set("size",9);
        history.set("settings.thresholds.NaN","STONE");
        rejection(() -> HistoryMenuSettings.fromSection(history, plugin.getLanguageService()),"NaN history threshold");
        history.set("settings.thresholds",null);
        history.set("settings.thresholds.0,5","INVALID_BLOCK");
        rejection(() -> HistoryMenuSettings.fromSection(history, plugin.getLanguageService()),"Invalid threshold material");
    }

    static void menuTests() throws Exception {
        TestMenu menu = new TestMenu();
        TestMenu second = new TestMenu();
        view = new View(inventory(null,9));
        menu.show(viewer);menu.show(viewer);second.show(viewer);
        check(tasks.size()==1 && menu.isLoading(), "One scheduled open and immediate load gate");
        drain();
        check(menu.loads==1 && second.loads==0,"One load per viewer across menus");
        check(menu.creates==0,"No inventory before DTO");
        Thread worker = new Thread(() -> menu.future.complete("ok"));
        worker.start();worker.join();
        check(menu.creates==0,"Completion cannot build inventory on worker");
        drain();
        check(menu.creates==1 && opens==1,"One completed inventory opened");
        TestMenu beforeStart = new TestMenu();
        beforeStart.show(viewer);
        AsyncMenu.cancelPending(viewer.getUniqueId());
        drain();
        check(beforeStart.loads==0, "Close before scheduled load cancels request");
        TestMenu canceled = new TestMenu();
        canceled.show(viewer);drain();
        AsyncMenu.cancelPending(viewer.getUniqueId());
        TestMenu spam = new TestMenu();
        for(int i=0;i<100;i++)spam.show(viewer);
        drain();
        check(spam.loads==0,"Canceled in-flight request still throttles queries");
        canceled.future.complete("late");drain();
        check(canceled.creates==0,"Closed request does not reopen inventory");
        spam.show(viewer);drain();
        view.top=inventory(null,9);
        spam.future.complete("late");drain();
        check(spam.creates==0,"Changed inventory does not reopen");
        TestMenu disabled = new TestMenu();
        disabled.show(viewer);drain();
        set(plugin,"isEnabled",false);
        disabled.future.complete("late");
        set(plugin,"isEnabled",true);
        TestMenu after = new TestMenu();
        after.show(viewer);drain();
        check(after.loads==1,"Disabled-plugin completion releases gate");
        AsyncMenu.cancelPending(viewer.getUniqueId());
        after.future.complete("late");drain();
        TestMenu clicked = new TestMenu();
        view=new View(inventory(clicked,9));
        MenuListener listener=new MenuListener();
        InventoryClickEvent top = new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,
                0,ClickType.LEFT,InventoryAction.PICKUP_ALL);
        listener.onClick(top);
        check(top.isCancelled() && clicked.callbackCancelled && clicked.clicks==1,
                "Top click canceled before action");
        InventoryClickEvent shift = new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,
                9,ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY);
        listener.onClick(shift);
        check(shift.isCancelled() && clicked.clicks==1,"Bottom shift click cannot transfer into GUI");
        InventoryClickEvent collect = new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,
                9,ClickType.DOUBLE_CLICK,InventoryAction.COLLECT_TO_CURSOR);
        listener.onClick(collect);
        check(collect.isCancelled(),"Bottom collect cannot collect GUI items");
        InventoryDragEvent drag = new InventoryDragEvent(view,new ItemStack(Material.STONE),
                new ItemStack(Material.STONE),false,Map.of(0,new ItemStack(Material.STONE)));
        listener.onDrag(drag);
        check(drag.isCancelled(),"Drag cannot insert GUI items");
    }

    @SuppressWarnings("unchecked")
    static void monitorTests() throws Exception {
        MonitorManager monitor = new MonitorManager(plugin);
        monitor.toggle(viewer,target);
        monitor.toggleChat(viewer,target);
        Method tick=MonitorManager.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        for(int i=0;i<10000;i++)tick.invoke(monitor);
        check(tasks.size()==1,"One pending monitor update per viewer");
        drain();
        MainConfigManager replacement = (MainConfigManager) unsafe.allocateInstance(MainConfigManager.class);
        LanguageTestSupport.update(replacement,"monitor.action_bar.waiting","new {player}");
        LanguageTestSupport.update(replacement,"monitor.target_left","left {player}");
        LanguageTestSupport.update(replacement,"monitor.chat.all_name","all");
        plugin.main=replacement;
        tick.invoke(monitor);drain();
        Map<UUID,Object> snapshots=(Map<UUID,Object>) get(monitor,"snapshots");
        snapshots.put(target.getUniqueId(),null);
        monitor.onQuit(new PlayerQuitEvent(target,"quit"));
        drain();
        check(!monitor.isMonitoring(viewer),"Target quit removes monitor sessions");
        check(((Map<?,?>)get(monitor,"chatSubscriptions")).isEmpty(),"Target quit removes chat subscriptions");
        check(snapshots.isEmpty(),"Target quit clears snapshots");
        check(get(monitor,"updateTask")==null,"Idle monitor timer stopped");
        monitor.toggle(viewer,target);
        monitor.toggleChat(viewer,null);
        monitor.onQuit(new PlayerQuitEvent(viewer,"quit"));
        check(((Map<?,?>)get(monitor,"chatSubscriptions")).isEmpty(),"Viewer quit removes all-target subscription");
        check(get(monitor,"updateTask")==null,"Viewer quit stops idle timer");
        monitor.shutdown();
    }

    @SuppressWarnings("unchecked")
    static void movementTests() throws Exception {
        HologramManager holograms=new HologramManager();
        set(holograms,"running",true);
        for(int i=0;i<10000;i++)holograms.handleMovement(target);
        check(tasks.isEmpty(),"Disabled holograms schedule no movement work");
        ((Set<UUID>)get(holograms,"enabledViewers")).add(viewer.getUniqueId());
        ((Map<UUID,Set<UUID>>)get(holograms,"viewersByTarget")).put(target.getUniqueId(),Set.of(viewer.getUniqueId()));
        ((Map<UUID,List<String>>)get(holograms,"hologramLinesByTarget")).put(target.getUniqueId(),List.of("a","b"));
        for(int i=0;i<10000;i++)holograms.handleMovement(target);
        check(tasks.size()==1,"Ten thousand movement events produce one pending capture");
        drain();
        check(locations==1,"One owner-thread position snapshot captured");
        holograms.handleMovement(target);
        check(tasks.size()==1,"Next movement accepted after capture");
        drain();
        check(locations==2,"Latest position can update after prior drain");
        holograms.stop();
    }

    @SuppressWarnings("unchecked")
    static void playerBatchTests() throws Exception {
        var settingsFolder = java.nio.file.Files.createTempDirectory("ui-model-settings");
        try (var input = UiLifecycleVerification.class.getClassLoader().getResourceAsStream("checks.yml")) {
            java.nio.file.Files.copy(input, settingsFolder.resolve("checks.yml"));
        }
        var checks = new ru.raveon.config.anticheat.ChecksConfigManager(LanguageTestSupport.plugin(settingsFolder));
        set(plugin, "configuration", new ru.raveon.config.RuntimeConfiguration(plugin.main, checks, null, null, null));
        Set<String> ids = new HashSet<>();
        for (int i=0;i<2501;i++) ids.add(UUID.randomUUID().toString());
        ids.add("invalid uuid");
        plugin.onlineService = new ru.raveon.service.PlayerOnlineService(null) {
            public CompletableFuture<Set<String>> getOnlinePlayerUuidsAsync(){
                return CompletableFuture.completedFuture(ids);
            }
        };
        List<List<UUID>> batches = new ArrayList<>();
        List<CompletableFuture<List<ru.raveon.database.model.PlayerAIProbabilityData>>> futures = new ArrayList<>();
        var storage = HologramPacketVerification.proxy(ru.raveon.database.storage.PlayerProbabilityStorage.class,
                (p,m,a) -> {
                    if(m.getName().equals("getPlayersData")){
                        List<UUID> batch = List.copyOf((Collection<UUID>)a[0]);
                        check(batch.size()<=1000, "Bounded SQL UUID batch");
                        check(a[2] == ru.raveon.model.ModelId.PRO, "Ranking did not select its model");
                        batches.add(batch);
                        var future = new CompletableFuture<List<ru.raveon.database.model.PlayerAIProbabilityData>>();
                        futures.add(future);
                        return future;
                    }
                    return HologramPacketVerification.defaultValue(m.getReturnType());
                });
        plugin.violation=(ru.raveon.manager.alert.ViolationManager)unsafe.allocateInstance(
                ru.raveon.manager.alert.ViolationManager.class);
        set(plugin.violation,"probabilityStorage",storage);
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("size",54);
        config.set("settings.player_slots",List.of(0,1,2));
        var menu = new ru.raveon.menu.players.PlayersMenu(PlayersMenuSettings.fromSection(config, plugin.getLanguageService()));
        Method load = menu.getClass().getDeclaredMethod("loadData");
        load.setAccessible(true);
        CompletableFuture<?> result = (CompletableFuture<?>)load.invoke(menu);
        check(batches.size()==1 && !result.isDone(), "Only first batch starts");
        futures.get(0).complete(List.of());
        check(batches.size()==2 && !result.isDone(), "Second batch waits for first");
        futures.get(1).complete(List.of());
        check(batches.size()==3 && !result.isDone(), "Third batch waits for second");
        futures.get(2).complete(List.of());
        check(result.isDone() && !result.isCompletedExceptionally(), "Menu handles more than 1000 online UUIDs");
        Set<UUID> loaded = new HashSet<>();
        batches.forEach(loaded::addAll);
        check(loaded.size()==2501, "No online UUIDs omitted or duplicated");
        var samples = new ArrayList<ru.raveon.database.model.PlayerAIProbabilityData>();
        for (int i = 0; i < 10; i++) samples.add(new ru.raveon.database.model.PlayerAIProbabilityData(
                new ru.raveon.api.models.data.PlayerData("Player" + i, UUID.randomUUID()), "test",
                new ArrayList<>(), 1, 1, ru.raveon.model.ModelId.PRO));
        Method apply = menu.getClass().getDeclaredMethod("applyData", List.class, Player.class);
        apply.setAccessible(true);
        apply.invoke(menu, samples, viewer);
        menu.nextPage();
        var cached = (CompletableFuture<?>) load.invoke(menu);
        check(cached.isDone() && cached.join() == samples && batches.size() == 3,
                "Pagination queried SQL instead of reusing the loaded ranking");
        menu.previousPage();
        load.invoke(menu);
        check(batches.size() == 3, "Back navigation reloaded SQL summaries");
    }

    public static void main(String[] args) throws Exception {
        Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(Unsafe)f.get(null);
        HologramPacketVerification.version=com.github.retrooper.packetevents.manager.server.ServerVersion.V_1_16_5;
        com.github.retrooper.packetevents.PacketEvents.setAPI(new HologramPacketVerification.Api());
        BukkitTask handle=HologramPacketVerification.proxy(BukkitTask.class,(p,m,a)->HologramPacketVerification.defaultValue(m.getReturnType()));
        BukkitScheduler scheduler=HologramPacketVerification.proxy(BukkitScheduler.class,(p,m,a)->{
            if(m.getName().equals("runTask") || m.getName().equals("runTaskLater")){
                tasks.add((Runnable)a[1]);return handle;
            }
            if(m.getName().startsWith("runTaskTimer"))return handle;
            return HologramPacketVerification.defaultValue(m.getReturnType());
        });
        Server server=HologramPacketVerification.proxy(Server.class,(p,m,a)->{
            return switch(m.getName()){
                case "getLogger" -> Logger.getLogger("test");
                case "getScheduler" -> scheduler;
                case "getPlayer" -> online.get(a[0]);
                case "getOnlinePlayers" -> online.values();
                case "getBukkitVersion" -> "1.16.5-R0.1";
                case "getVersion","getName" -> "test";
                case "isPrimaryThread" -> executing;
                default -> HologramPacketVerification.defaultValue(m.getReturnType());
            };
        });
        Bukkit.setServer(server);
        viewer=player("viewer");target=player("target");
        plugin=(TestPlugin)unsafe.allocateInstance(TestPlugin.class);
        set(plugin,"isEnabled",true);
        Raveon.INSTANCE=plugin;
        plugin.main=(MainConfigManager)unsafe.allocateInstance(MainConfigManager.class);
        LanguageTestSupport.update(plugin.main,"monitor.action_bar.waiting","wait {player}");
        LanguageTestSupport.update(plugin.main,"monitor.target_left","left {player}");
        LanguageTestSupport.update(plugin.main,"monitor.chat.all_name","all");
        plugin.hologram=(HologramConfigManager)unsafe.allocateInstance(HologramConfigManager.class);
        set(plugin.hologram,"enabled",true);
        configurationTests();
        menuTests();
        monitorTests();
        playerBatchTests();
        movementTests();
        System.out.println("UI PASS " + assertions + " assertions");
    }
}
