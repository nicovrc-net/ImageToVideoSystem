package net.nicovrc.dev;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.imageio.ImageIO;
import javax.xml.crypto.Data;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HTTPServer extends Thread {

    private final int HTTPPort = 8888;
    private final Pattern HTTPVersion = Pattern.compile("HTTP/(\\d+\\.\\d+)");
    private final Pattern HTTPMethod = Pattern.compile("^(GET|HEAD)");
    private final Pattern HTTPURI = Pattern.compile("(GET|HEAD) (.+) HTTP/");
    private final Pattern VideoIDMatch = Pattern.compile("(GET|HEAD) /(.+)_(\\d+)");
    private final Pattern UrlMatch = Pattern.compile("(GET|HEAD) /\\?url=(.+) HTTP");

    private final HashMap<String, VideoData> DataList = new HashMap<>();

    private final String ProxyIP;
    private final int ProxyPort;

    private final String RedisServer;
    private final int RedisPort;
    private final String RedisPass;

    private final String Hostname;


    public HTTPServer() throws Exception {
        if (!new File("./temp").exists()){
            boolean mkdir = new File("./temp").mkdir();
            if (!mkdir){
                throw new Exception("フォルダ生成失敗");
            }
        }

        ProxyIP = "";
        ProxyPort = 3128;

        YamlMapping input = Yaml.createYamlInput(new File("./config.yml")).readYamlMapping();

        RedisServer = input.string("RedisServer");
        RedisPort = input.integer("RedisPort");
        RedisPass = input.string("RedisPass");

        Hostname = "i2v.nicovrc.net";

        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                new Thread(()->{
                    HashMap<String, VideoData> temp = new HashMap<>(DataList);
                    temp.forEach((id, videodata)->{
                        long l = new Date().getTime() - videodata.getVideoCreateTime();
                        if (l >= 864000000L){
                            if (videodata.getVideoHost().equals(Hostname)){
                                new File("./temp/"+videodata.getVideoID()+".ts").delete();
                            }
                            DataList.remove(id);
                            JedisPool jedisPool = new JedisPool(RedisServer, RedisPort);
                            Jedis jedis = jedisPool.getResource();
                            if (!RedisPass.isEmpty()){
                                jedis.auth(RedisPass);
                            }

                            jedis.del("nico-img2:ExecuteLog:"+id);
                            jedis.close();
                            jedisPool.close();

                        }
                    });
                }).start();

                new Thread(()->{
                    JedisPool jedisPool = new JedisPool(RedisServer, RedisPort);
                    Jedis jedis = jedisPool.getResource();
                    if (!RedisPass.isEmpty()){
                        jedis.auth(RedisPass);
                    }

                    jedis.keys("nico-img2:CacheLog:*").forEach((id)->{
                        VideoData videoData = new Gson().fromJson(jedis.get(id), VideoData.class);
                        long l = new Date().getTime() - videoData.getVideoCreateTime();

                        if (l >= 864000000L){
                            if (videoData.getVideoHost().equals(Hostname)){
                                new File("./temp/"+videoData.getVideoID()+".ts").delete();
                            }
                            jedis.del("nico-img2:ExecuteLog:"+id);
                        }
                    });
                    jedis.close();
                    jedisPool.close();
                }).start();
            }
        }, 0L, 60000L);

    }

    @Override
    public void run() {

        try {
            ServerSocket svSock = new ServerSocket(HTTPPort);
            System.out.println("[Info] TCP Port " + HTTPPort + "で 処理受付用HTTPサーバー待機開始");

            final boolean[] temp = {true};
            while (temp[0]) {
                try {
                    System.gc();
                    Socket sock = svSock.accept();
                    new Thread(() -> {
                        try {
                            final InputStream in = sock.getInputStream();
                            final OutputStream out = sock.getOutputStream();

                            byte[] data = new byte[1000000];
                            int readSize = in.read(data);
                            if (readSize <= 0) {
                                sock.close();
                                return;
                            }
                            data = Arrays.copyOf(data, readSize);

                            final String httpRequest = new String(data, StandardCharsets.UTF_8);
                            final String httpVersion = getHTTPVersion(httpRequest);

                            System.out.println(httpRequest);

                            if (httpVersion == null){
                                out.write("HTTP/1.1 502 Bad Gateway\nContent-Type: text/plain; charset=utf-8\n\nbad gateway".getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                in.close();
                                out.close();
                                sock.close();

                                return;
                            }

                            Matcher matcher = HTTPMethod.matcher(httpRequest);
                            if (!matcher.find()){
                                out.write(("HTTP/"+httpVersion+" 405 Method Not Allowed\nContent-Type: text/plain; charset=utf-8\n\n405").getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                in.close();
                                out.close();
                                sock.close();

                                return;
                            }
                            final boolean isGET = matcher.group(1).toLowerCase(Locale.ROOT).equals("get");
                            matcher = HTTPURI.matcher(httpRequest);

                            if (!matcher.find()){
                                out.write(("HTTP/"+httpVersion+" 502 Bad Gateway\nContent-Type: text/plain; charset=utf-8\n\n").getBytes(StandardCharsets.UTF_8));
                                if (isGET){
                                    out.write(("bad gateway").getBytes(StandardCharsets.UTF_8));
                                }
                                out.flush();
                                in.close();
                                out.close();
                                sock.close();

                                return;
                            }
                            final String URIText = matcher.group(2);
                            //System.out.println(URIText);

                            if (URIText.startsWith("/?get_data")){
                                // 情報
                                out.write(("HTTP/"+httpVersion+" 200 OK\nContent-Type: application/json; charset=utf-8\n\n").getBytes(StandardCharsets.UTF_8));
                                if (isGET){
                                    out.write(new Gson().toJson(new OutputJson(Constant.Version, DataList.size())).getBytes(StandardCharsets.UTF_8));
                                }
                                out.flush();

                                in.close();
                                out.close();
                                sock.close();
                                return;
                            }

                            if (URIText.startsWith("/?url=")){
                                // 画像から動画生成
                                final OkHttpClient.Builder builder = new OkHttpClient.Builder();
                                final OkHttpClient client = ProxyIP.isEmpty() ? new OkHttpClient() : builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ProxyIP, ProxyPort))).build();

                                matcher = UrlMatch.matcher(httpRequest);
                                final String url;
                                if (matcher.find()){
                                    url = matcher.group(2);
                                } else {
                                    url = "";
                                }
                                //System.out.println(url);

                                if (!url.startsWith("http")){
                                    out.write(("HTTP/"+httpVersion+" 404 Not Found\nContent-Type: text/plain; charset=utf-8\n\n").getBytes(StandardCharsets.UTF_8));
                                    if (isGET){
                                        out.write(("URL Not Found").getBytes(StandardCharsets.UTF_8));
                                    }
                                    out.flush();
                                    in.close();
                                    out.close();
                                    sock.close();

                                    new Thread(()->{
                                        LogData logData = new LogData();
                                        logData.setLogId(UUID.randomUUID().toString() + "-" + new Date().getTime());
                                        logData.setTime(new Date().getTime());
                                        logData.setHTTPRequest(httpRequest);
                                        logData.setRequestURL(url);
                                        logData.setErrorMessage("Not URL");

                                        JedisPool jedisPool = new JedisPool(RedisServer, RedisPort);
                                        Jedis jedis = jedisPool.getResource();
                                        if (!RedisPass.isEmpty()){
                                            jedis.auth(RedisPass);
                                        }

                                        jedis.set("nico-img2:ExecuteLog:"+logData.getLogId(), new Gson().toJson(logData));
                                        jedis.close();
                                        jedisPool.close();
                                    }).start();

                                    return;
                                }

                                boolean[] isFound = {false};
                                DataList.forEach((ID, VideoData)->{
                                    if (isFound[0]){
                                        return;
                                    }
                                    if (VideoData.getImageURL().equals(url)){
                                        isFound[0] = true;

                                        try {
                                            out.write(("HTTP/" + httpVersion + " 302 Found\nDate: " + new Date() + "\nLocation: /" + VideoData.getVideoID() + "/main.m3u8" + "\n\n").getBytes(StandardCharsets.UTF_8));
                                            if (isGET){
                                                out.write(("jump to /"+VideoData.getVideoID()+"/main.m3u8").getBytes(StandardCharsets.UTF_8));
                                            }
                                            out.flush();
                                            in.close();
                                            out.close();
                                            sock.close();
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }

                                        new Thread(()->{
                                            LogData logData = new LogData();
                                            logData.setLogId(UUID.randomUUID().toString() + "-" + new Date().getTime());
                                            logData.setTime(new Date().getTime());
                                            logData.setHTTPRequest(httpRequest);
                                            logData.setRequestURL(url);
                                            logData.setErrorMessage("");

                                            JedisPool jedisPool = new JedisPool(RedisServer, RedisPort);
                                            Jedis jedis = jedisPool.getResource();
                                            if (!RedisPass.isEmpty()){
                                                jedis.auth(RedisPass);
                                            }

                                            jedis.set("nico-img2:ExecuteLog:"+logData.getLogId(), new Gson().toJson(logData));
                                            jedis.close();
                                            jedisPool.close();
                                        }).start();
                                    }
                                });
                                if (isFound[0]){
                                    return;
                                }

                                // 画像かどうかチェック
                                Request request_html = new Request.Builder()
                                        .url(url)
                                        .addHeader("User-Agent", Constant.UserAgent)
                                        .head()
                                        .build();
                                Response response = client.newCall(request_html).execute();
                                String header = response.header("Content-Type");
                                if (header == null){
                                    header = response.header("content-type");
                                }

                                //System.out.println(url + " : " + header);

                                if (header == null || !header.startsWith("image")){
                                    try {
                                        out.write(("HTTP/"+httpVersion+" 404 Not Found\nContent-Type: text/plain; charset=utf-8\n\n").getBytes(StandardCharsets.UTF_8));
                                        if (isGET){
                                            out.write(("Not Image").getBytes(StandardCharsets.UTF_8));
                                        }
                                        out.flush();
                                        in.close();
                                        out.close();
                                        sock.close();
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }

                                    new Thread(()->{
                                        LogData logData = new LogData();
                                        logData.setLogId(UUID.randomUUID().toString() + "-" + new Date().getTime());
                                        logData.setTime(new Date().getTime());
                                        logData.setHTTPRequest(httpRequest);
                                        logData.setRequestURL(url);
                                        logData.setErrorMessage("Not Image");

                                        JedisPool jedisPool = new JedisPool(RedisServer, RedisPort);
                                        Jedis jedis = jedisPool.getResource();
                                        if (!RedisPass.isEmpty()){
                                            jedis.auth(RedisPass);
                                        }

                                        jedis.set("nico-img2:ExecuteLog:"+logData.getLogId(), new Gson().toJson(logData));
                                        jedis.close();
                                        jedisPool.close();
                                    }).start();

                                    return;
                                }
                                response.close();

                                final String fileId = UUID.randomUUID().toString().split("-")[0] + "_" + new Date().getTime();

                                // 画像DL
                                Request request_image = new Request.Builder()
                                        .url(url)
                                        .addHeader("User-Agent", Constant.UserAgent)
                                        .build();
                                response = client.newCall(request_image).execute();
                                if (response.body() != null){
                                    byte[] bytes = response.body().bytes();
                                    if (new File("./temp/temp-"+fileId).exists()){
                                        new File("./temp/temp-"+fileId).delete();
                                    }
                                    FileOutputStream stream = new FileOutputStream("./temp/temp-" + fileId);
                                    stream.write(bytes);
                                    stream.close();
                                }
                                response.close();

                                // 動画生成
                                BufferedImage read = ImageIO.read(new File("./temp/temp-" + fileId));
                                int width = (read.getWidth() * 2) / 2;
                                int height = (read.getHeight() * 2) / 2;

                                if (width >= 1920){
                                    height = (int) (height * ((double) (1920 / width)));
                                }
                                if (height >= 1920){
                                    width = (int) (width * ((double) (1920 / height)));
                                }

                                String[] command = {"/bin/ffmpeg","-loop","1","-i","./temp/temp-"+fileId,"-i","./out.mp3","-c:v","libx264","-vf","transpose=0","-vf","scale="+width+":"+height,"-pix_fmt","yuv420p","-c:a","copy","-map","0:v:0","-map","1:a:0","-t","5","-r","60","./temp/"+fileId+".ts"};
                                Runtime runtime = Runtime.getRuntime();
                                Process exec = runtime.exec(command);
                                exec.waitFor();

                                System.out.println(exec.exitValue());
                                String s = exec.inputReader().readLine();
                                while (s != null) {
                                    System.out.println(s);
                                    s = exec.inputReader().readLine();
                                }

                                new File("./temp/temp-" + fileId).delete();

                                String m3u8 = "#EXTM3U\n"+
                                        "#EXT-X-VERSION:3\n" +
                                        "#EXT-X-TARGETDURATION:5\n" +
                                        "#EXT-X-MEDIA-SEQUENCE:0\n" +
                                        "#EXTINF:5.000000,\n" +
                                        "#hostname#/"+fileId+"/start.ts\n" +
                                        "#EXTINF:5.000000,\n" +
                                        "#hostname#/"+fileId+"/#id#.ts";

                                VideoData videoData = new VideoData();
                                videoData.setVideoID(fileId);
                                videoData.setVideoCreateTime(new Date().getTime());
                                videoData.setVideoHost(Hostname);
                                videoData.setM3u8(m3u8);
                                videoData.setImageURL(url);
                                DataList.put(fileId, videoData);

                                new Thread(()->{

                                    LogData logData = new LogData();
                                    logData.setLogId(UUID.randomUUID().toString() + "-" + new Date().getTime());
                                    logData.setTime(new Date().getTime());
                                    logData.setHTTPRequest(httpRequest);
                                    logData.setRequestURL(url);
                                    logData.setErrorMessage("");

                                    JedisPool jedisPool = new JedisPool(RedisServer, RedisPort);
                                    Jedis jedis = jedisPool.getResource();
                                    if (!RedisPass.isEmpty()){
                                        jedis.auth(RedisPass);
                                    }

                                    jedis.set("nico-img2:CacheLog:"+videoData.getVideoID(), new Gson().toJson(videoData));
                                    jedis.set("nico-img2:ExecuteLog:"+logData.getLogId(), new Gson().toJson(logData));
                                    jedis.close();
                                    jedisPool.close();
                                }).start();

                                out.write(("HTTP/" + httpVersion + " 302 Found\nDate: " + new Date() + "\nLocation: /" + fileId + "/main.m3u8" + "\n\n").getBytes(StandardCharsets.UTF_8));
                                if (isGET){
                                    out.write(("jump to /"+fileId+"/main.m3u8").getBytes(StandardCharsets.UTF_8));
                                }
                                out.flush();
                                in.close();
                                out.close();
                                sock.close();

                                return;
                            }

                            matcher = VideoIDMatch.matcher(httpRequest);
                            if (matcher.find()){
                                final String VideoID = matcher.group(2)+"_"+matcher.group(3);
                                final VideoData VideoData = DataList.get(VideoID) != null ? DataList.get(VideoID) : getRedisData(VideoID);

                                //System.out.println(VideoID);

                                if (VideoData == null){
                                    out.write(("HTTP/"+httpVersion+" 404 Not Found\nContent-Type: text/plain; charset=utf-8\n\n").getBytes(StandardCharsets.UTF_8));
                                    if (isGET){
                                        out.write("404".getBytes(StandardCharsets.UTF_8));
                                    }
                                    out.flush();

                                    in.close();
                                    out.close();
                                    sock.close();
                                    return;
                                }

                                //System.out.println("VideoData Found");

                                if (URIText.endsWith("main.m3u8")){
                                    final String t = VideoData.getM3u8().replaceAll("#hostname#", DataList.get(VideoID) != null ? "" : "https://"+Hostname).replaceAll("#id#", UUID.randomUUID().toString().split("-")[0]);

                                    out.write(("HTTP/"+httpVersion+" 200 OK\nContent-Type: application/vnd.apple.mpegurl; charset=utf-8\n\n").getBytes(StandardCharsets.UTF_8));
                                    if (isGET){
                                        out.write(t.getBytes(StandardCharsets.UTF_8));
                                    }
                                    out.flush();

                                    in.close();
                                    out.close();
                                    sock.close();
                                    return;
                                }

                                if (URIText.endsWith(".ts")){
                                    File file = new File("./temp/" + VideoID + ".ts");
                                    if (!file.exists()){
                                        out.write(("HTTP/"+httpVersion+" 404 Not Found\nContent-Type: text/plain; charset=utf-8\n\n").getBytes(StandardCharsets.UTF_8));
                                        if (isGET){
                                            out.write("404".getBytes(StandardCharsets.UTF_8));
                                        }
                                        out.flush();

                                        in.close();
                                        out.close();
                                        sock.close();
                                        return;
                                    }


                                    out.write(("HTTP/"+httpVersion+" 200 OK\nContent-Type: video/mp2t; charset=utf-8\n\n").getBytes(StandardCharsets.UTF_8));
                                    if (isGET){
                                        FileInputStream stream = new FileInputStream(file);
                                        out.write(stream.readAllBytes());
                                        stream.close();
                                    }
                                    out.flush();

                                    in.close();
                                    out.close();
                                    sock.close();
                                    return;
                                }
                            }

                            out.write(("HTTP/"+httpVersion+" 404 Not Found\nContent-Type: text/plain; charset=utf-8\n\n404").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            in.close();
                            out.close();
                            sock.close();
                        } catch (Exception e){
                            throw new RuntimeException(e);
                        }
                    }).start();
                } catch (Exception e) {
                    e.printStackTrace();
                    temp[0] = false;
                }
            }
        } catch (Exception e){
            e.printStackTrace();
        }

    }

    private String getHTTPVersion(String HTTPRequest){
        Matcher matcher = HTTPVersion.matcher(HTTPRequest);
        if (matcher.find()){
            return matcher.group(1);
        }

        return null;
    }

    private VideoData getRedisData(String VideoID){
        JedisPool jedisPool = new JedisPool(RedisServer, RedisPort);
        Jedis jedis = jedisPool.getResource();
        if (!RedisPass.isEmpty()){
            jedis.auth(RedisPass);
        }

        String s1 = jedis.get("nico-img2:CacheLog" + VideoID);
        if (s1 != null){
            VideoData videoData = new Gson().fromJson(s1, VideoData.class);
            jedis.close();
            jedisPool.close();
            return videoData;
        }
        return null;
    }
}
