package xyz.n7mn;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.amihaiemil.eoyaml.YamlSequence;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

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

    private static String[] proxyList = new String[0];

    public static void main(String[] args) {

        if (new File("./config.yml").exists()){
            try {
                YamlMapping ConfigYaml = Yaml.createYamlInput(new File("./config.yml")).readYamlMapping();
                baseUrl = ConfigYaml.string("BaseURL");
                ffmpegPass = ConfigYaml.string("ffmpegPass");
            } catch (Exception e){
                e.printStackTrace();
                return;
            }
        } else {

            YamlMappingBuilder add = Yaml.createYamlMappingBuilder()
                    .add("BaseURL", baseUrl)
                    .add("ffmpegPass", "/bin/ffmpeg");
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

        // HTTP通信を受け取る
        new Thread(()->{
            try {
                ServerSocket socket = new ServerSocket(8888);
                while (true) {
                    System.gc();
                    Socket sock = socket.accept();

                    final String proxyAddress;
                    final int proxyPort;
                    if (proxyList.length > 0){
                        int i = new SecureRandom().nextInt(0, proxyList.length);
                        String[] split = proxyList[i].split(":");
                        proxyAddress = split[0];
                        proxyPort = Integer.parseInt(split[1]);
                    } else {
                        proxyAddress = "";
                        proxyPort = 0;
                    }

                    new Thread(() -> {
                        try {
                            byte[] data = new byte[100000000];
                            InputStream in = sock.getInputStream();
                            OutputStream out = sock.getOutputStream();

                            int readSize = in.read(data);
                            data = Arrays.copyOf(data, readSize);
                            String text = new String(data, StandardCharsets.UTF_8);
                            Matcher matcher1 = Pattern.compile("GET /\\?url=(.*) HTTP").matcher(text);
                            Matcher matcher2 = Pattern.compile("HTTP/1\\.(\\d)").matcher(text);
                            Matcher matcher3 = Pattern.compile("GET /video/(.*) HTTP").matcher(text);

                            //System.out.println(text);

                            String videoUri = "";
                            String httpVersion = "1";

                            String errorMessage = "";

                            if (matcher1.find()) {
                                try {
                                    videoUri = createVideo(matcher1.group(1), proxyAddress, proxyPort);
                                } catch (Exception e) {
                                    errorMessage = e.getMessage();
                                }
                            }

                            if (matcher2.find()) {
                                httpVersion = matcher2.group(1);
                            }


                            byte[] httpText;

                            if (matcher3.find()) {
                                //System.out.println("get video");
                                httpText = getVideo(matcher3.group(1), "1." + httpVersion);

                                out.write(httpText);
                                out.flush();
                                in.close();
                                out.close();
                                sock.close();

                                return;
                            }

                            if (videoUri.isEmpty() && errorMessage.isEmpty()) {
                                //System.out.println("error");
                                httpText = ("HTTP/1." + httpVersion + " 405 Method Not Allowed").getBytes(StandardCharsets.UTF_8);
                            } else if (!errorMessage.isEmpty()) {
                                //System.out.println("error2");
                                httpText = ("HTTP/1." + httpVersion + " 403 Forbidden\r\n\r\n" + errorMessage).getBytes(StandardCharsets.UTF_8);
                            } else {
                                //System.out.println("create video");
                                httpText = ("HTTP/1."+httpVersion+" 302 Found\n" +
                                        "Date: "+new Date()+"\n" +
                                        "Location: " + baseUrl + "video/" + videoUri + "\n\njump to "+baseUrl + "video/" + videoUri).replaceAll("\0","").getBytes(StandardCharsets.UTF_8);
                            }

                            out.write(httpText);
                            out.flush();
                            in.close();
                            out.close();
                            sock.close();

                        } catch (Exception e){
                            throw new RuntimeException(e);
                        }
                    }).start();
                }
            } catch (Exception e){
                e.printStackTrace();
            }
        }).start();


    }


    private static String createVideo(String url, String ProxyIP, int ProxyPort) throws Exception {

        // 画像DL
        final OkHttpClient.Builder builder = new OkHttpClient.Builder();
        final OkHttpClient client = ProxyIP.isEmpty() ? new OkHttpClient() : builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ProxyIP, ProxyPort))).build();

        Request request_html = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0 ImageToVideoSystem/1.0 (https://nicovrc.net/)")
                .build();
        Response response = client.newCall(request_html).execute();

        String mineType = "";
        if (response.body() != null){
            mineType = Objects.requireNonNull(response.body().contentType()).type();
        }
        response.close();

        // 画像以外は拒否
        if (!mineType.toLowerCase(Locale.ROOT).startsWith("image")){
            throw new FileNotSupportException("Not ImageType");
        }

        if (response.code() != 200){
            throw new FileNotSupportException("HTTP Code : " + response.code());
        }

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(url.getBytes(StandardCharsets.UTF_8));
        byte[] cipher_byte = md.digest();
        StringBuilder sb = new StringBuilder(2 * cipher_byte.length);
        for(byte b: cipher_byte) {
            sb.append(String.format("%02x", b&0xff) );
        }
        String fileId = sb.substring(0, 16);

        if (new File("./temp/"+fileId).exists()){
            return fileId+"/1.ts";
        }

        new File("./temp/"+fileId).mkdir();

        String str1 = ffmpegPass+" -loop 1 -i "+url+" -c:v libx264 -t 1 -r 1 ./temp/"+fileId+"/1.ts";
        //String str1 = "ffmpeg -loop 1 -i "+url+" -c:v libx264 -t 1 -r 1 ./temp/"+fileId+"/1.ts";

        new Thread(()->{
            try {
                Runtime runtime = Runtime.getRuntime();
                Process exec = runtime.exec(str1);
                exec.waitFor();
            } catch (InterruptedException | IOException e) {
                throw new RuntimeException(e);
            }
        }).start();

        return fileId+"/1.ts";

    }

    private static byte[] getVideo(String uri, String httpVersion){
        //System.out.println("debug : uri = "+uri);

        File file = new File("./temp/" + uri);
        if (!file.exists()){
            return ("HTTP/"+httpVersion+" 404 Not Found\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n404").getBytes(StandardCharsets.UTF_8);
        }

        if (!file.isFile()){
            return ("HTTP/"+httpVersion+" 403 Forbidden\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n403").getBytes(StandardCharsets.UTF_8);
        }

        String ContentType = "application/octet-stream";
        if (file.getName().endsWith("m3u8")){
            ContentType = "application/vnd.apple.mpegurl";
        }
        if (file.getName().endsWith("ts")){
            ContentType = "video/mp2t";
        }


        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            byte[] temp = ("HTTP/" + httpVersion + " 200 OK\r\n" +
                    "Date: " + new Date() + "\r\n" +
                    "Content-Type: "+ContentType+"\r\n" +
                    "\r\n").getBytes(StandardCharsets.UTF_8);

            byte[] read = fileInputStream.readAllBytes();

            byte[] result = new byte[temp.length + read.length];
            System.arraycopy(temp, 0, result, 0, temp.length);
            System.arraycopy(read, 0, result, temp.length, read.length);

            return result;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}