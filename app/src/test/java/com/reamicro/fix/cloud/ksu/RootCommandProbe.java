package com.reamicro.fix.cloud.ksu;

import java.nio.charset.StandardCharsets;

public class RootCommandProbe {
    public static void main(String[] arguments) throws Exception {
        if (arguments[0].equals("sleep")) {
            Thread.sleep(60_000);
            return;
        }
        if (arguments[0].equals("duplex")) {
            byte[] output = new byte[1024 * 1024];
            java.util.Arrays.fill(output, (byte) 'x');
            System.out.write(output);
            System.out.flush();
        }
        String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        System.out.write(input.getBytes(StandardCharsets.UTF_8));
    }
}
