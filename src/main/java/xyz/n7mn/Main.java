package xyz.n7mn;

import com.amihaiemil.eoyaml.*;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;



public class Main {
    private static String baseUrl = "http://localhost:8888/";
    private static String ffmpegPass = "/bin/ffmpeg";

    private static String RedisServer = "localhost";
    private static int RedisPort = 6379;
    private static String RedisPass = "";

    private static String[] proxyList = new String[0];

    private static String[] otherServer = new String[0];

    public static void main(String[] args) {

        if (new File("./config.yml").exists()){
            try {
                YamlMapping ConfigYaml = Yaml.createYamlInput(new File("./config.yml")).readYamlMapping();
                baseUrl = ConfigYaml.string("BaseURL");
                ffmpegPass = ConfigYaml.string("ffmpegPass");
                RedisServer = ConfigYaml.string("RedisServer");
                RedisPort = ConfigYaml.integer("RedisPort");
                RedisPass = ConfigYaml.string("RedisPass");

                YamlSequence list = ConfigYaml.yamlSequence("OtherServer");
                if (list != null){
                    otherServer = new String[list.size()];
                    for (int i = 0; i < list.size(); i++){
                        otherServer[i] = list.string(i);
                    }
                }


            } catch (Exception e){
                e.printStackTrace();
                return;
            }
        } else {

            YamlMappingBuilder add = Yaml.createYamlMappingBuilder()
                    .add("BaseURL", baseUrl)
                    .add("ffmpegPass", "/bin/ffmpeg")
                    .add("RedisServer", "127.0.0.1")
                    .add("RedisPort", "6379")
                    .add("RedisPass", "xxx")
                    .add("OtherServer", Yaml.createYamlSequenceBuilder().add("https://server1.example.com/").build());
            YamlMapping build = add.build();

            try {
                new File("./config.yml").createNewFile();
                PrintWriter writer = new PrintWriter("./config.yml");
                writer.print(build.toString());
                writer.close();

                System.out.println("[Info] config.ymlを設定してください。");
                return;
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

        }

        if (new File("./proxy.yml").exists()){
            try {
                YamlMapping ConfigYaml = Yaml.createYamlInput(new File("./proxy.yml")).readYamlMapping();
                YamlSequence list = ConfigYaml.yamlSequence("Proxy");

                if (list != null) {
                    proxyList = new String[list.size()];
                    for (int i = 0; i < list.size(); i++) {
                        proxyList[i] = list.string(i);
                    }
                }

            } catch (Exception e){
                e.printStackTrace();
                return;
            }
        } else {

            try {
                new File("./proxy.yml").createNewFile();
                PrintWriter writer = new PrintWriter("./proxy.yml");
                writer.print("""
                        # ProxyIP:ProxyPort
                        Proxy:
                        #   - 127.0.0.1:3128""");
                writer.close();

            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

        }

        // 無音の音声ファイルがなかったら用意
        if (!new File("./out.mp3").exists()){
            try {
                Runtime runtime = Runtime.getRuntime();
                Process exec = runtime.exec(ffmpegPass+" -f lavfi -i anullsrc=r=44100:cl=mono -t 5 -aq 1 -c:a libmp3lame out.mp3");
                exec.waitFor();
            } catch (Exception e){
                e.printStackTrace();
                return;
            }
        }

        // HTTP通信を受け取る
        ServerSocket socket = null;
        try {
            socket = new ServerSocket(8888);
            while (true) {
                Socket sock = socket.accept();
                System.gc();
                new Thread(() -> {
                    try {

                        final String proxyAddress;
                        final int proxyPort;
                        if (proxyList.length > 0) {
                            int i = new SecureRandom().nextInt(0, proxyList.length);
                            String[] split = proxyList[i].split(":");
                            proxyAddress = split[0];
                            proxyPort = Integer.parseInt(split[1]);
                        } else {
                            proxyAddress = "";
                            proxyPort = 0;
                        }

                        byte[] data = new byte[100000000];
                        InputStream in = sock.getInputStream();
                        OutputStream out = sock.getOutputStream();

                        int readSize = in.read(data);
                        data = Arrays.copyOf(data, readSize);
                        final String text = new String(data, StandardCharsets.UTF_8);
                        Matcher matcher1 = Pattern.compile("GET /\\?url=(.*) HTTP").matcher(text);
                        Matcher matcher2 = Pattern.compile("HTTP/1\\.(\\d)").matcher(text);
                        Matcher matcher3 = Pattern.compile("GET /video/(.*) HTTP").matcher(text);

                        //System.out.println(text);

                        String videoUri = "";
                        String httpVersion = "1";
                        String requestUrl = "";

                        String errorMessage = "";

                        if (matcher1.find()) {
                            try {
                                requestUrl = matcher1.group(1);

                                String s = checkVideo(requestUrl);
                                if (s != null){
                                    // すでに他の鯖にあったらその鯖へ誘導する
                                    byte[] b = ("HTTP/1." + httpVersion + " 302 Found\n" +
                                            "Date: " + new Date() + "\n" +
                                            "Location: " + s + "\n\njump to " + s).replaceAll("\0", "").getBytes(StandardCharsets.UTF_8);

                                    out.write(b);
                                    out.flush();
                                    out.close();
                                    in.close();
                                    sock.close();

                                    String finalRequestUrl1 = requestUrl;
                                    new Thread(()->{
                                        LogData logData = new LogData(UUID.randomUUID().toString() + "-" + new Date().getTime(), new Date().getTime(), text, finalRequestUrl1, "");

                                        JedisPool jedisPool = new JedisPool(RedisServer, RedisPort);
                                        Jedis jedis = jedisPool.getResource();
                                        if (!RedisPass.isEmpty()){
                                            jedis.auth(RedisPass);
                                        }

                                        jedis.set("nico-img:ExecuteLog:"+logData.getLogId(), new Gson().toJson(logData));
                                        jedis.close();
                                        jedisPool.close();
                                    }).start();

                                    return;
                                }

                                videoUri = createVideo(requestUrl, proxyAddress, proxyPort);
                            } catch (Exception e) {
                                errorMessage = e.getMessage();
                            }
                        }

                        if (matcher2.find()) {
                            httpVersion = matcher2.group(1);
                        }


                        byte[] httpText = null;

                        //System.out.println("!");
                        if (matcher3.find()) {
                            //System.out.println("?!");
                            //System.out.println("get video");
                            String[] split = matcher3.group(1).split("/");
                            File file = new File("./temp/" + split[0]);
                            if (!file.exists()){
                                //System.out.println(split[0]);
                                String url = checkFile(split[0]);
                                //System.out.println(url);
                                if (url != null){
                                    httpText = ("HTTP/1." + httpVersion + " 302 Found\n" +
                                            "Date: " + new Date() + "\n" +
                                            "Location: " + url + "\n\njump to " + url).replaceAll("\0", "").getBytes(StandardCharsets.UTF_8);
                                } else {
                                    httpText = ("HTTP/1."+httpVersion+" 404 Not Found\n" +
                                            "Content-Type: text/plain\n" +
                                            "\n404").getBytes(StandardCharsets.UTF_8);
                                }
                            }

                            if (split[1].endsWith(".ts")){
                                file = new File("./temp/" + split[0] + "/1.ts");
                            } else {
                                file = new File("./temp/" + split[0] + "/" + split[1]);
                            }

                            if (httpText != null){
                                out.write(httpText);
                                out.flush();
                                in.close();
                                out.close();
                                sock.close();
                                return;
                            }

                            String ContentType = "application/octet-stream";
                            if (file.getName().endsWith("m3u8")){
                                ContentType = "application/vnd.apple.mpegurl";
                            }
                            if (file.getName().endsWith("ts")){
                                ContentType = "video/mp2t";
                            }

                            //System.out.println("!?");
                            try {
                                FileInputStream fileInputStream = new FileInputStream(file);
                                out.write(("HTTP/1." + httpVersion + " 200 OK\r\n" +
                                        "Date: " + new Date() + "\r\n" +
                                        "Content-Type: "+ContentType+"\r\n" +
                                        "\r\n").getBytes(StandardCharsets.UTF_8));
                                out.write(fileInputStream.readAllBytes());
                                out.flush();
                                in.close();
                                out.close();
                                sock.close();
                                return;

                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }

                        }

                        final String finalRequestUrl = requestUrl;
                        final String finalErrorMessage = errorMessage;
                        new Thread(()->{
                            LogData logData = new LogData(UUID.randomUUID().toString() + "-" + new Date().getTime(), new Date().getTime(), text, finalRequestUrl, finalErrorMessage);

                            JedisPool jedisPool = new JedisPool(RedisServer, RedisPort);
                            Jedis jedis = jedisPool.getResource();
                            if (!RedisPass.isEmpty()){
                                jedis.auth(RedisPass);
                            }

                            jedis.set("nico-img:ExecuteLog:"+logData.getLogId(), new Gson().toJson(logData));
                            jedis.close();
                            jedisPool.close();
                        }).start();


                        //System.out.println("??");
                        if (errorMessage != null && videoUri.isEmpty()) {
                            //System.out.println("error");
                            httpText = ("HTTP/1." + httpVersion + " 405 Method Not Allowed").getBytes(StandardCharsets.UTF_8);

                            out.write(httpText);
                            out.flush();
                            in.close();
                            out.close();
                            sock.close();

                            return;
                        }

                        if (!errorMessage.isEmpty()) {
                            //System.out.println("error2");
                            httpText = ("HTTP/1." + httpVersion + " 403 Forbidden\r\n\r\n" + errorMessage).getBytes(StandardCharsets.UTF_8);

                            out.write(httpText);
                            out.flush();
                            in.close();
                            out.close();
                            sock.close();

                            return;
                        }

                        //System.out.println("create video");
                        httpText = ("HTTP/1." + httpVersion + " 302 Found\n" +
                                "Date: " + new Date() + "\n" +
                                "Location: " + baseUrl + "video/" + videoUri + "\n\njump to " + baseUrl + "video/" + videoUri).replaceAll("\0", "").getBytes(StandardCharsets.UTF_8);

                        out.write(httpText);
                        out.flush();
                        in.close();
                        out.close();
                        sock.close();

                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).start();
            }

        } catch (Exception e) {
            e.fillInStackTrace();
            try {
                if (socket == null){
                    return;
                }
                socket.close();
            } catch (IOException ex) {
                ex.fillInStackTrace();
            }
        }


    }

    private static String checkFile(String fileId) {
        try {
            if (otherServer == null || otherServer.length == 0){
                return null;
            }

            final OkHttpClient client = new OkHttpClient();
            for (String str : otherServer){
                //System.out.println(str + "video/"+fileId+"/main.m3u8");
                Request request_html = new Request.Builder()
                        .url(str + "video/"+fileId+"/main.m3u8")
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/113.0 ImageToVideoSystem/1.0 (https://nicovrc.net/)")
                        .build();
                Response response = client.newCall(request_html).execute();

                if (response.code() != 200){
                    response.close();
                    continue;
                }

                response.close();
                return str+"video/"+fileId+"/main.m3u8";

            }

            return null;
        } catch (Exception e){
            e.printStackTrace();
            return null;
        }
    }

    private static String checkVideo(String requestUrl) {

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(requestUrl.getBytes(StandardCharsets.UTF_8));
            byte[] cipher_byte = md.digest();
            StringBuilder sb = new StringBuilder(2 * cipher_byte.length);
            for(byte b: cipher_byte) {
                sb.append(String.format("%02x", b&0xff) );
            }
            String fileId = sb.substring(0, 16);

            if (otherServer == null || otherServer.length == 0){
                return null;
            }

            final OkHttpClient client = new OkHttpClient();
            for (String str : otherServer){
                Request request_html = new Request.Builder()
                        .url(str + "video/"+fileId+"/main.m3u8")
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/113.0 ImageToVideoSystem/1.0 (https://nicovrc.net/)")
                        .build();
                Response response = client.newCall(request_html).execute();

                if (response.code() != 200){
                    response.close();
                    continue;
                }

                response.close();
                return str+"video/"+fileId+"/main.m3u8";

            }

        } catch (Exception e){
            e.printStackTrace();
            return null;
        }

        return null;
    }


    private static String createVideo(String url, String ProxyIP, int ProxyPort) throws Exception {

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(url.getBytes(StandardCharsets.UTF_8));
        byte[] cipher_byte = md.digest();
        StringBuilder sb = new StringBuilder(2 * cipher_byte.length);
        for(byte b: cipher_byte) {
            sb.append(String.format("%02x", b&0xff) );
        }
        String fileId = sb.substring(0, 16);


        if (new File("./temp/"+fileId+"/main.m3u8").exists()){
            return fileId+"/main.m3u8";
        }

        // 画像DL
        final OkHttpClient.Builder builder = new OkHttpClient.Builder();
        final OkHttpClient client = ProxyIP.isEmpty() ? new OkHttpClient() : builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ProxyIP, ProxyPort))).build();

        Request request_html = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/113.0 ImageToVideoSystem/1.0 (https://nicovrc.net/)")
                .build();
        Response response = client.newCall(request_html).execute();

        String mineType = "";
        if (response.body() != null){
            mineType = Objects.requireNonNull(response.body().contentType()).type();
            byte[] bytes = response.body().bytes();
            if (new File("./temp/temp-"+fileId).exists()){
                new File("./temp/temp-"+fileId).delete();
            }
            FileOutputStream stream = new FileOutputStream("./temp/temp-" + fileId);
            stream.write(bytes);
            stream.close();
        }
        response.close();

        // 画像以外は拒否
        if (!mineType.toLowerCase(Locale.ROOT).startsWith("image")){
            throw new FileNotSupportException("Not ImageType");
        }

        if (response.code() != 200){
            throw new FileNotSupportException("HTTP Code : " + response.code());
        }

        //if (new File("./temp/"+fileId).exists()){
        //    return fileId+"/main.m3u8";
        //}

        if (!new File("./temp/"+fileId).exists()){
            new File("./temp/"+fileId).mkdir();
        }

        if (!new File("./temp/"+fileId+"/1.ts").exists()){

            String str1 = ffmpegPass+" -loop 1 -i ./temp/temp-"+fileId+" -i ./out.mp3 -c:v libx264 -preset ultrafast -crf 16 -pix_fmt yuv420p -c:a copy -map 0:v:0 -map 1:a:0 -t 5 -r 15 ./temp/"+fileId+"/1.ts";
            //String str1 = "ffmpeg -loop 1 -i "+url+" -c:v libx264 -t 1 -r 1 ./temp/"+fileId+"/1.ts";

            try {
                Runtime runtime = Runtime.getRuntime();
                Process exec = runtime.exec(str1);
                exec.waitFor();

                new File("./temp/temp-"+fileId).delete();
            } catch (IOException e) {
                e.fillInStackTrace();
            }
        }

        int[] i = {0};

        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                byte[] read = """
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:5
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:5.000000,
#id#.ts
                """.replaceAll("#id#", UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8);

                if (i[0] == 0){
                    try {
                        FileOutputStream ts_stream = new FileOutputStream("./temp/" + fileId + "/main.m3u8");
                        ts_stream.write(read);
                        ts_stream.close();
                    } catch (Exception e){
                        e.fillInStackTrace();
                        timer.cancel();
                    }
                } else {
                    File file = new File("./temp/" + fileId + "/main.m3u8");
                    try {
                        FileWriter filewriter = new FileWriter(file, true);
                        filewriter.write("#EXTINF:5.000000,\n" +
                                "#id#.ts\n".replaceAll("#id#", UUID.randomUUID().toString()));
                        filewriter.close();
                    } catch (Exception e){
                        e.fillInStackTrace();
                        timer.cancel();
                    }
                }
                i[0]++;

                if (i[0] >= 28800){
                    timer.cancel();
                    new File("./temp/"+fileId+"/main.m3u8").delete();
                    new File("./temp/"+fileId+"/1.ts").delete();
                    new File("./temp/"+fileId).delete();
                }
            }
        }, 0L, 3000L);

        return fileId+"/main.m3u8";

    }
}