package cn.tidescan.strategy;

import static cn.tidescan.strategy.Strategy.*;
import org.junit.jupiter.api.Test;

public class StrategySmokeTest {
    @Test
    void strategyBoundaries() { main(new String[0]); }
    public static void main(String[] args) {
        var p = Parameters.defaults();
        var prior = new Bar(10, 10.5, 10_000_000, 300_000_000);
        var yesterday = new Bar(10.6, 10.65, 5_000_000, 200_000_000);
        var today = new Bar(10.6, 10.8, 8_000_000, 250_000_000);
        check(matches(prior, yesterday, today, 200_000_000, p), "matching red doji");
        check(!matches(prior, yesterday, today, 0, p), "unknown float rejected");
        check(!matches(prior, yesterday, new Bar(10.6, 10.8, 8_000_000, 249_999_999), 200_000_000, p), "amount threshold");
        check(!matches(prior, yesterday, new Bar(10.6, 10.8, 20_000_000, 300_000_000), 200_000_000, p), "strict volume limit");
        check(!matches(prior, yesterday, today, 300_000_000, p), "turnover threshold");
        System.out.println("Strategy smoke tests passed");
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
