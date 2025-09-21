package com.sivalabs.demo.util;

import java.security.SecureRandom;

public class TicketIdGenerator {
    private static final SecureRandom rnd = new SecureRandom();
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static String generate() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            s.append(LETTERS.charAt(rnd.nextInt(LETTERS.length())));
        }
        int num = rnd.nextInt(100);
        if (num < 10) s.append("0");
        s.append(num);
        return s.toString();
    }
}
