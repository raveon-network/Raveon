package ru.raveon.utils.math;

import lombok.Getter;

import java.util.List;

public final class GraphUtil {

    public static GraphResult getGraph(List<Double> values) {
        StringBuilder graph = new StringBuilder();

        double largest = 0;

        for (double value : values) {
            if (value > largest) {
                largest = value;
            }
        }

        int graphHeight = 2;
        int positives = 0;
        int negatives = 0;

        for (int i = graphHeight - 1; i > 0; i -= 1) {
            StringBuilder sb = new StringBuilder();

            for (double index : values) {
                double value = graphHeight * index / (largest == 0 ? 1 : largest);

                if (value > i && value < i + 1) {
                    positives++;
                    sb.append("+");
                } else {
                    negatives++;
                    sb.append("-");
                }
            }

            graph.append(sb);
        }

        return new GraphResult(graph.toString(), positives, negatives);
    }

    @Getter
    public static class GraphResult {
        private final String graph;
        private final int positives;
        private final int negatives;

        public GraphResult(String graph, int positives, int negatives) {
            this.graph = graph;
            this.positives = positives;
            this.negatives = negatives;
        }

    }
}
