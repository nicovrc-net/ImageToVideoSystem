package net.nicovrc.dev;

public class Main {

    public static void main(String[] args) {

        try {
            new HTTPServer().start();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

}
