package net.nicovrc.dev;

public class OutputJson {
    private String Version;
    private int CacheCount;

    public OutputJson(String Version, int CacheCount){
        this.CacheCount = CacheCount;
        this.Version = Version;
    }

    public String getVersion() {
        return Version;
    }

    public int getCacheCount() {
        return CacheCount;
    }

}
