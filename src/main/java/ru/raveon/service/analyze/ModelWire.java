package ru.raveon.service.analyze;

import com.google.flatbuffers.FlatBufferBuilder;

import ru.raveon.api.models.RotationFrame;
import ru.raveon.api.models.UltraFrame;
import ru.raveon.model.ModelId;

import java.nio.*;
import java.util.List;

public final class ModelWire {
    private ModelWire() {}

    public record Result(ModelId model, double probability, int status) {}

    public static byte[] pro(List<RotationFrame> frames) {
        float[] values = new float[frames.size() * 8];
        int i = 0;
        for (RotationFrame f : frames) {
            values[i++] = f.getDeltaYaw();
            values[i++] = f.getDeltaPitch();
            values[i++] = f.getAccelYaw();
            values[i++] = f.getAccelPitch();
            values[i++] = f.getJerkYaw();
            values[i++] = f.getJerkPitch();
            values[i++] = f.getGcdErrorYaw();
            values[i++] = f.getGcdErrorPitch();
        }
        return request(ModelId.PRO, "", frames.size(), values);
    }

    public static byte[] ultra(String player, List<UltraFrame> frames) {
        float[] values = new float[frames.size() * ModelId.ULTRA.features()];
        int at = 0;
        for (UltraFrame frame : frames) {
            float[] row = frame.inferenceFeatures();
            System.arraycopy(row, 0, values, at, row.length);
            at += row.length;
        }
        return request(ModelId.ULTRA, player, frames.size(), values);
    }

    public static byte[] request(ModelId model, String player, int count, float[] values) {
        if (count <= 0 || values.length != count * model.features())
            throw new IllegalArgumentException("Frame/feature count mismatch");
        FlatBufferBuilder b = new FlatBufferBuilder(64 + values.length * 4);
        b.startVector(4, values.length, 4);
        for (int i = values.length - 1; i >= 0; i--) {
            if (model == ModelId.ULTRA && !Float.isFinite(values[i]))
                throw new IllegalArgumentException("Nonfinite feature");
            b.addFloat(values[i]);
        }
        int vector = b.endVector();
        int name = player == null || player.isEmpty() ? 0 : b.createString(player);
        b.startTable(5);
        b.addOffset(0, name, 0);
        b.addInt(1, count, 0);
        b.addOffset(2, vector, 0);
        b.addInt(3, model.wireId(), 0);
        b.addInt(4, model == ModelId.ULTRA ? 3 : 2, 0);
        b.finish(b.endTable(), "GAIQ");
        return b.sizedByteArray();
    }

    public static byte[] batch(List<byte[]> items) {
        int size = 2;
        for (byte[] b : items) size += 4 + b.length;
        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) items.size());
        for (byte[] b : items) out.putInt(b.length).put(b);
        return out.array();
    }

    public static Result[] decode(byte[] body, List<ModelId> expected) {
        if (body == null || body.length < 6)
            throw new IllegalArgumentException("Truncated GAIB response");
        ByteBuffer in = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        if (in.get() != 'G' || in.get() != 'A' || in.get() != 'I' || in.get() != 'B')
            throw new IllegalArgumentException("Expected GAIB");
        int count = Short.toUnsignedInt(in.getShort());
        if (count != expected.size() || in.remaining() < count * 8)
            throw new IllegalArgumentException("Response count mismatch");
        double[] scores = new double[count];
        for (int i = 0; i < count; i++) scores[i] = in.getDouble();
        Result[] results = new Result[count];
        if (!in.hasRemaining()) {
            // An old backend can serve Pro only; Ultra may never silently fall back to Pro.
            for (int i = 0; i < count; i++) {
                if (expected.get(i) != ModelId.PRO)
                    throw new IllegalArgumentException("Ultra needs a typed backend response");
                results[i] = new Result(ModelId.PRO, valid(scores[i]), 0);
            }
            return results;
        }
        if (in.remaining() != 6 + count * 2
                || in.get() != 'R'
                || in.get() != 'V'
                || in.get() != 'M'
                || in.get() != '2'
                || Short.toUnsignedInt(in.getShort()) != count)
            throw new IllegalArgumentException("Invalid RVM2 metadata");
        for (int i = 0; i < count; i++) {
            ModelId model = ModelId.fromWire(Byte.toUnsignedInt(in.get()));
            int status = Byte.toUnsignedInt(in.get());
            if (model != expected.get(i) || status > 2)
                throw new IllegalArgumentException("Model/result association mismatch");
            results[i] = new Result(model, status == 0 ? valid(scores[i]) : 0, status);
        }
        return results;
    }

    private static double valid(double x) {
        if (!Double.isFinite(x) || x < 0 || x > 1)
            throw new IllegalArgumentException("Invalid probability");
        return x;
    }
}
