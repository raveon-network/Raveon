package ru.raveon.service.analyze;

import com.google.gson.*;
import ru.raveon.model.ModelId;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Executable transport fault checks; no live Bukkit server is required. */
public final class StreamIsolationContractTest {
    private static final UUID PLAYER=UUID.fromString("11111111-1111-4111-8111-111111111111");

    public static void run() throws Exception {
        var pro=new StreamWindowCursor(PLAYER,ModelId.PRO,50,10);
        var ultra=new StreamWindowCursor(PLAYER,ModelId.ULTRA,64,16);
        var first=pro.request(pro.offer(frames(0,50,ModelId.PRO,0)));
        check(!pro.claim(first),"No unacknowledged result can affect buffer");
        pro.acknowledge(first,first.end());
        check(pro.claim(first) && !pro.claim(first),"A repeated answer changes buffer once");
        var delta=pro.request(pro.offer(frames(10,50,ModelId.PRO,0)));
        check(delta.key().equals(first.key()) && delta.from()==51 && delta.end()==60 && rows(delta)==10,"Pro sends its own delta");
        var uf=ultra.request(ultra.offer(frames(0,64,ModelId.ULTRA,0)));
        ultra.acknowledge(uf,uf.end());
        var ud=ultra.request(ultra.offer(frames(16,64,ModelId.ULTRA,0)));
        check(ud.from()==65 && ud.end()==80 && rows(ud)==16,"Ultra has an independent range");
        reject(() -> pro.acknowledge(ud,ud.end()),"Other model acknowledgement rejected");
        reject(() -> pro.acknowledge(delta,delta.end()-1),"Partial acknowledgement rejected");
        var other=UUID.fromString("33333333-3333-4333-8333-333333333333");
        var otherPro=new StreamWindowCursor(other,ModelId.PRO,50,10);
        var op=otherPro.request(otherPro.offer(frames(0,50,ModelId.PRO,10000)));
        var otherUltra=new StreamWindowCursor(other,ModelId.ULTRA,64,16);
        var ou=otherUltra.request(otherUltra.offer(frames(0,64,ModelId.ULTRA,10000)));
        reject(() -> pro.acknowledge(op,op.end()),"Other player's acknowledgement rejected");

        var expected=List.of(delta,ud,op,ou);
        JsonObject reply=reply(expected);
        check(StreamWire.decode(bytes(reply),expected,"12345678").size()==4,"Mixed players/models match exact requests");
        for (String field:List.of("k","model","h","end","p","status")) {
            var bad=reply.deepCopy(); var result=bad.getAsJsonArray("results").get(0).getAsJsonObject();
            if (field.equals("k")) result.addProperty(field,op.key().toString());
            else result.addProperty(field,field.equals("p") ? 1.1 : 999);
            reject(() -> StreamWire.decode(bytes(bad),expected,"12345678"),"Wrong association rejected: "+field);
        }
        var swapped=reply.deepCopy(); var values=swapped.getAsJsonArray("results");
        var saved=values.get(0); values.set(0,values.get(2)); values.set(2,saved);
        reject(() -> StreamWire.decode(bytes(swapped),expected,"12345678"),"Same-model results for different players cannot swap");
        var wrongType=reply.deepCopy();wrongType.getAsJsonArray("results").get(0).getAsJsonObject().addProperty("h","60");
        reject(() -> StreamWire.decode(bytes(wrongType),expected,"12345678"),"Text acknowledgement is not a number");
        reject(() -> StreamWire.decode(bytes(reply),expected,"87654321"),"Stale profile response rejected");
        reject(() -> StreamWire.decode(bytes(reply),List.of(delta),"12345678"),"Missing/extra result count rejected");

        pro.acknowledge(delta,delta.end());
        check(pro.claim(delta) && !pro.claim(first),"Old response cannot apply after a new window");
        pro.offer(frames(20,50,ModelId.PRO,0));
        var combined=pro.request(pro.offer(frames(30,50,ModelId.PRO,0)));
        check(combined.from()==61 && combined.end()==80 && rows(combined)==20,"Coalesced snapshots preserve range");
        pro.acknowledge(combined,combined.end());
        var broken=pro.request(pro.offer(frames(900,50,ModelId.PRO,0)));
        check(!broken.key().equals(delta.key()) && broken.from()==1 && rows(broken)==50,"Gap starts a fresh full stream");
        check(!pro.claim(delta),"Old stream response cannot affect new stream");

        Files.createDirectories(Path.of("build"));
        Files.write(Path.of("build/stream-isolation.bin"),ModelWire.batch(expected.stream().map(StreamWindowCursor.Request::bytes).toList()));
        randomizedCursors();
        System.out.println("Stream isolation, model/player association, stale replies, deltas and 600 randomized cursor steps passed");
    }

    private static void randomizedCursors() {
        Random random=new Random(81273);
        List<StreamWindowCursor> cursors=new ArrayList<>();
        List<ModelId> models=new ArrayList<>();
        List<UUID> players=new ArrayList<>();
        int[] starts=new int[20];
        for(int i=0;i<20;i++) {
            ModelId model=i%2==0?ModelId.PRO:ModelId.ULTRA;
            UUID player=new UUID(0,i/2+1);
            models.add(model);players.add(player);
            cursors.add(new StreamWindowCursor(player,model,model.sequence(),model.step()));
        }
        for(int iteration=0;iteration<600;iteration++) {
            int index=random.nextInt(20); var cursor=cursors.get(index); ModelId model=models.get(index);
            int offers=iteration<20?1:random.nextInt(4)+1;
            StreamWindowCursor.Offer offer=null;
            for(int i=0;i<offers;i++) {
                offer=cursor.offer(frames(starts[index],model.sequence(),model,index*10000));
                starts[index]+=model.step();
            }
            var request=cursor.request(offer);
            check(request.player().equals(players.get(index)) && request.model()==model,"Cursor identity is immutable");
            check(rows(request)<=model.sequence() && rows(request)>0,"Delta is bounded by its model window");
            cursor.acknowledge(request,request.end());
            check(cursor.claim(request) && !cursor.claim(request),"Randomized duplicate answer is applied once");
            int other=(index+1)%20;
            check(!cursors.get(other).claim(request),"A different cursor cannot claim a result");
        }
    }

    private static byte[] frames(int start,int count,ModelId model,int base) {
        float[] values=new float[count*model.features()];
        for(int i=0;i<values.length;i++)values[i]=base+start*model.features()+i;
        return ModelWire.raw(values);
    }
    private static int rows(StreamWindowCursor.Request request) {
        return ByteBuffer.wrap(request.bytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(46);
    }
    private static JsonObject reply(List<StreamWindowCursor.Request> expected) {
        var root=new JsonObject(); root.addProperty("v",1);root.addProperty("profile_crc","12345678");
        var array=new JsonArray();root.add("results",array);
        for(var request:expected) {
            var value=new JsonObject();array.add(value);
            value.addProperty("k",request.key().toString());value.addProperty("model",request.model().wireId());
            value.addProperty("h",request.end());value.addProperty("end",request.end());
            value.addProperty("p",.91);value.addProperty("status",0);
        }
        return root;
    }
    private static byte[] bytes(JsonObject node) {return node.toString().getBytes(StandardCharsets.UTF_8);}
    private static void reject(Runnable action,String message) {
        boolean rejected=false;
        try{action.run();}catch(RuntimeException expected){rejected=true;}
        check(rejected,message);
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
