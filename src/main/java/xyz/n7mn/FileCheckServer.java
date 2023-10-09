package xyz.n7mn;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class FileCheckServer extends Thread {

    @Override
    public void run() {

        String baseUrl = "";
        if (new File("./config.yml").exists()){
            try {
                YamlMapping ConfigYaml = Yaml.createYamlInput(new File("./config.yml")).readYamlMapping();
                baseUrl = ConfigYaml.string("BaseURL");

            } catch (Exception e){
                e.printStackTrace();
                return;
            }
        }

        while (true){
            try {
                DatagramSocket sock = new DatagramSocket(8888);

                byte[] data = new byte[10000];
                DatagramPacket packet = new DatagramPacket(data, data.length);
                sock.receive(packet);

                if (packet.getLength() == 0){
                    sock.close();
                    continue;
                }

                String s = new String(Arrays.copyOf(packet.getData(), packet.getLength()));

                //System.out.println("受信 : "+s);
                InetSocketAddress address = new InetSocketAddress(packet.getAddress(), packet.getPort());

                try {
                    JsonElement json = new Gson().fromJson(s, JsonElement.class);
                    String str = json.getAsJsonObject().get("check").getAsString();
                    if (new File("./temp/"+str+"/main.m3u8").exists()){
                        byte[] bytes = ("{\"result\": \""+baseUrl+"video/"+str+"/main.m3u8\"}").getBytes(StandardCharsets.UTF_8);
                        sock.send(new DatagramPacket(bytes, bytes.length, address));
                        sock.close();
                        continue;
                    }
                } catch (Exception e) {
                    byte[] bytes = "{\"result\": \"no found\"}".getBytes(StandardCharsets.UTF_8);
                    sock.send(new DatagramPacket(bytes, bytes.length, address));
                    sock.close();
                    continue;
                }

                byte[] bytes = "{\"result\": \"no found\"}".getBytes(StandardCharsets.UTF_8);
                sock.send(new DatagramPacket(bytes, bytes.length, address));
                sock.close();

                data = null;
                System.gc();
            } catch (Exception e){
                e.printStackTrace();
                return;
            }
        }
    }
}
