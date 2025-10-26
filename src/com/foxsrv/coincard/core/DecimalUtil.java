package com.foxsrv.coincard.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class DecimalUtil {
    public static double truncate(double value, int scale) {
        if (scale < 0) scale = 0;
        BigDecimal bd = BigDecimal.valueOf(value);
        bd = bd.setScale(scale, RoundingMode.DOWN);
        return bd.doubleValue();
    }
}
