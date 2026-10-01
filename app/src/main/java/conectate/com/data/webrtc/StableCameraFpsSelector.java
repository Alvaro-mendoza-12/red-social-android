package conectate.com.data.webrtc;

/** Selects only an advertised AE range, favoring steady cadence at its closest upper limit. */
public final class StableCameraFpsSelector {
    private StableCameraFpsSelector() {}

    /** Values use the same units; WebRTC passes thousandths of a frame per second. */
    public static int selectIndex(int[] minimums, int[] maximums, int requested) {
        if (minimums.length != maximums.length || requested <= 0) {
            throw new IllegalArgumentException("Invalid camera cadence request");
        }
        int selected = -1;
        for (int index = 0; index < minimums.length; index++) {
            if (minimums[index] < 0 || maximums[index] <= 0 || minimums[index] > maximums[index]) continue;
            if (selected < 0 || isBetter(index, selected, minimums, maximums, requested)) selected = index;
        }
        if (selected < 0) throw new IllegalArgumentException("No supported camera cadence range");
        return selected;
    }

    private static boolean isBetter(int candidate, int current, int[] mins, int[] maxs, int requested) {
        long candidateDistance = Math.abs((long) maxs[candidate] - requested);
        long currentDistance = Math.abs((long) maxs[current] - requested);
        if (candidateDistance != currentDistance) return candidateDistance < currentDistance;
        // On equal distance, avoid exceeding a deliberately lower request.
        if (maxs[candidate] != maxs[current]) return maxs[candidate] < maxs[current];
        boolean candidateFits = mins[candidate] <= requested;
        boolean currentFits = mins[current] <= requested;
        if (candidateFits != currentFits) return candidateFits;
        return mins[candidate] > mins[current];
    }
}
