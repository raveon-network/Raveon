package ru.raveon.verification;

import ru.raveon.api.models.RotationFrame;
import ru.raveon.api.models.UltraFrame;
import ru.raveon.model.ModelId;
import ru.raveon.service.analyze.ModelWire;
import ru.raveon.utils.math.RotationRingBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DualModelVerification {
    public static void main(String[] args) {
        RotationRingBuffer<Integer> buffer = new RotationRingBuffer<>(50);
        for (int i = 0; i < 49; i++) buffer.addFrame(i);
        require(buffer.pollSnapshot(10) == null, "incomplete Pro window");
        buffer.addFrame(49);
        List<Integer> first = buffer.pollSnapshot(10);
        require(first.size() == 50 && first.get(0) == 0 && first.get(49) == 49, "first Pro window");
        for (int i = 50; i < 59; i++) buffer.addFrame(i);
        require(buffer.pollSnapshot(10) == null, "Pro step fired early");
        buffer.addFrame(59);
        require(buffer.pollSnapshot(10).get(0) == 10, "overlapping Pro window");
        buffer.clear();
        require(buffer.pollSnapshot(10) == null, "clear retained window");
        List<RotationFrame> pro = new ArrayList<>();
        for (int i = 0; i < 50; i++) pro.add(new RotationFrame(i, i + 1, i + 2, i + 3, i + 4, i + 5, i + 6, i + 7));
        byte[] proBytes = ModelWire.pro(pro);
        verifyRequest(proBytes, 50, 1, 8, 0, 56);
        float[] values = new float[24];
        for (int i = 0; i < values.length; i++) values[i] = i + 1;
        UltraFrame frame = UltraFrame.fromFeatures(values);
        List<UltraFrame> ultra = new ArrayList<>();
        for (int i = 0; i < 64; i++) ultra.add(frame);
        byte[] ultraBytes = ModelWire.ultra("fixture", ultra);
        float[] features = frame.inferenceFeatures();
        verifyRequest(ultraBytes, 64, 2, ModelId.ULTRA.features(), features[0], features[features.length - 1]);
        require(pro.get(0).toCsvRow(true).equals("1,0.000000,1.000000,2.000000,3.000000,4.000000,5.000000,6.000000,7.000000"), "Pro CSV columns/precision");
        require(frame.datasetFeatures().length == 152 && features.length == 152, "Ultra CSV/wire widths");
        ByteBuffer batch = ByteBuffer.wrap(ModelWire.batch(List.of(proBytes, ultraBytes))).order(ByteOrder.LITTLE_ENDIAN);
        require(Short.toUnsignedInt(batch.getShort()) == 2, "batch count");
        require(batch.getInt() == proBytes.length, "first payload length");
        byte[] copy = new byte[proBytes.length];
        batch.get(copy);
        require(Arrays.equals(copy, proBytes), "first payload changed");
        require(batch.getInt() == ultraBytes.length && batch.remaining() == ultraBytes.length, "second payload framing");
        ByteBuffer response = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        response.put(new byte[] {'G', 'A', 'I', 'B'}).putShort((short) 2).putDouble(.2).putDouble(.9);
        response.put(new byte[] {'R', 'V', 'M', '2'}).putShort((short) 2).put((byte) 1).put((byte) 0).put((byte) 2).put((byte) 0);
        ModelWire.Result[] result = ModelWire.decode(response.array(), List.of(ModelId.PRO, ModelId.ULTRA));
        require(result[0].probability() == .2 && result[1].probability() == .9, "model/result association");
        byte[] invalid = response.array().clone();
        invalid[30] = 1;
        rejected(() -> ModelWire.decode(invalid, List.of(ModelId.PRO, ModelId.ULTRA)));
        byte[] invalidScore = response.array().clone();
        ByteBuffer.wrap(invalidScore).order(ByteOrder.LITTLE_ENDIAN).putDouble(6, Double.NaN);
        rejected(() -> ModelWire.decode(invalidScore, List.of(ModelId.PRO, ModelId.ULTRA)));
        rejected(() -> ModelWire.decode(Arrays.copyOf(response.array(), 10), List.of(ModelId.PRO, ModelId.ULTRA)));
        if (Boolean.getBoolean("verify.packaged"))
            require(ModelWire.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar"), "packaged classes not loaded");
        System.out.println("DualModelVerification passed: Pro windows/CSV, Ultra widths, request vectors, batch framing, typed results, rejection paths");
    }

    private static void verifyRequest(byte[] bytes, int count, int model, int width, float first, float last) {
        require(new String(bytes, 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("GAIQ"), "request identifier");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int table = b.getInt(0);
        int vtable = table - b.getInt(table);
        int countOffset = Short.toUnsignedInt(b.getShort(vtable + 6));
        int vectorOffset = Short.toUnsignedInt(b.getShort(vtable + 8));
        int modelOffset = Short.toUnsignedInt(b.getShort(vtable + 10));
        require(b.getInt(table + countOffset) == count, "frame count");
        require(b.getInt(table + modelOffset) == model, "wire model");
        int vector = table + vectorOffset + b.getInt(table + vectorOffset);
        require(b.getInt(vector) == count * width, "feature count");
        require(b.getFloat(vector + 4) == first, "first feature");
        require(b.getFloat(vector + 4 * count * width) == last, "last feature");
    }

    private static void rejected(Runnable runnable) {
        try { runnable.run(); throw new AssertionError("invalid response accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
