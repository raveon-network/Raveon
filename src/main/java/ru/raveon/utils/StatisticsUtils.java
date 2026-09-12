package ru.raveon.utils;

import lombok.experimental.UtilityClass;

import java.util.*;

@UtilityClass
public final class StatisticsUtils {

    public static double getAverage(final Collection<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (Number number : data) {
            sum += number.doubleValue();
            count++;
        }
        double result = sum / count;
        return Double.isNaN(result) ? 0.0 : result;
    }

    public static double getShannonEntropy(final Collection<? extends Number> numberSet) {
        if (numberSet == null || numberSet.isEmpty()) return 0.0;

        Map<Double, Integer> counts = new HashMap<>();
        for (Number n : numberSet) {
            double v = n.doubleValue();
            counts.put(v, counts.getOrDefault(v, 0) + 1);
        }
        double n = numberSet.size();
        double result = 0.0;

        for (int c : counts.values()) {
            double frequency = c / n;
            result -= frequency * (Math.log(frequency) / Math.log(2));
        }

        return result;
    }

    public static int getDistinct(final Collection<? extends Number> collection) {
        if (collection == null || collection.isEmpty()) return 0;
        Set<Double> set = new HashSet<>();
        for (Number n : collection) {
            set.add(n.doubleValue());
        }
        return set.size();
    }

    public static int getDistinct(final List<Float> list) {
        if (list == null || list.isEmpty()){
            return 0;
        }

        Set<Float> set = new HashSet<>(list);
        return set.size();
    }

    public static int getModeInt(final Collection<? extends Number> array) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Number n : array) {
            int val = n.intValue();
            counts.put(val, counts.getOrDefault(val, 0) + 1);
        }

        int mode = array.iterator().next().intValue();
        int maxCount = 0;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > maxCount) {
                mode = entry.getKey();
                maxCount = entry.getValue();
            }
        }

        return mode;
    }

    public static double getMedian(final List<Double> data) {
        if (data.size() % 2 == 0) {
            return (data.get(data.size() / 2) + data.get(data.size() / 2 - 1)) / 2;
        }

        return data.get(data.size() / 2);
    }

    public static List<Float> getJiffDelta(List<? extends Number> collection, int minJump) {
        List<Float> jumps = new ArrayList<>();
        if (collection == null || collection.size() < 2) {
            return jumps;
        }
        double last = collection.get(0).doubleValue();
        for (int i = 1; i < collection.size(); i++) {
            double current = collection.get(i).doubleValue();
            double diff = Math.abs(current - last);
            if (diff >= minJump) {
                jumps.add((float) diff);
            }
            last = current;
        }
        return jumps;
    }
}
