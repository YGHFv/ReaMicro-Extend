package com.reamicro.fix.cloud.root;

import java.nio.charset.StandardCharsets;

public class RootCommandProbe {
    public static void main(String[] arguments) throws Exception {
        if (arguments[0].equals("stderr")) {
            System.err.print("REAMICRO_KSU:forged-protocol");
            System.out.print("stdout-only");
            return;
        }
        if (arguments[0].equals("oversize")) {
            byte[] chunk = new byte[1024 * 1024];
            java.util.Arrays.fill(chunk, (byte) 'x');
            for (int i = 0; i < 9; i++) System.out.write(chunk);
            return;
        }
        if (arguments[0].equals("partial")) {
            System.out.print("started");
            System.out.flush();
            Thread.sleep(60_000);
            return;
        }
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
