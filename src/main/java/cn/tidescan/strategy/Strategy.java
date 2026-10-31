package cn.tidescan.strategy;

/** Three-day strategy. Volumes are shares, amount is yuan and turnover is percent. */
public final class Strategy {
    private Strategy() {}

    public record Bar(double open, double close, double volume, double amount) {}
    public record Parameters(double red, double green, double priorBody, double todayBody,
                             double gap, double volumeRatio, double turnover, double amount) {
        public static Parameters defaults() { return new Parameters(1.5, 1, 1, 1, 5, 2, 3, 250_000_000); }
        public Parameters {
            if (!Double.isFinite(red) || !Double.isFinite(green) || !Double.isFinite(priorBody)
                || !Double.isFinite(todayBody) || !Double.isFinite(gap) || !Double.isFinite(volumeRatio)
                || !Double.isFinite(turnover) || !Double.isFinite(amount)
                || red < 0 || green < 0 || priorBody < 0 || todayBody < 0 || gap < 0
                || volumeRatio <= 0 || turnover < 0 || amount < 0)
                throw new IllegalArgumentException("Invalid strategy parameters");
        }
    }

    /** Returns false on missing data; unknown historical float must never be guessed. */
    public static boolean matches(Bar prior, Bar yesterday, Bar today, double floatShares, Parameters p) {
        if (!valid(prior) || !valid(yesterday) || !valid(today) || p == null
            || !Double.isFinite(floatShares) || floatShares <= 0) return false;
        double o2 = prior.open, c2 = prior.close, v2 = prior.volume;
        double o1 = yesterday.open, c1 = yesterday.close, v1 = yesterday.volume;
        double o = today.open, c = today.close, v = today.volume;
        boolean red = c1 >= o1 && Math.abs(c1 - o1) / o1 <= p.red / 100;
        boolean green = c1 < o1 && Math.abs(c1 - o1) / o1 <= p.green / 100;
        boolean structure = c2 > o2 && o1 > o2 && c1 > o2 && c > o
                && (red || (green && c > o1));
        boolean priorBody = c2 > o2 && Math.abs(c2 - o2) / o2 * 100 >= p.priorBody;
        boolean todayBody = c > o && Math.abs(c - o) / o * 100 >= p.todayBody;
        boolean price1 = c > c1 && o < o1;
        boolean price2 = c > c1 && o >= Math.min(o1, c1) && o <= Math.max(o1, c1);
        boolean price3 = c > o && o > c1 && (o - c1) / c1 <= p.gap / 100
                && ((red && c1 > o1) || green);
        boolean volume = v1 < v && v < p.volumeRatio * v2 && v1 < v2;
        double turnover = v * 100 / floatShares;
        return (red || green) && structure && priorBody && todayBody
                && (price1 || price2 || price3) && volume
                && turnover >= p.turnover && today.amount >= p.amount;
    }

    private static boolean valid(Bar b) {
        return b != null && Double.isFinite(b.open) && Double.isFinite(b.close)
                && Double.isFinite(b.volume) && Double.isFinite(b.amount)
                && b.open > 0 && b.close > 0 && b.volume >= 0 && b.amount >= 0;
    }
}
