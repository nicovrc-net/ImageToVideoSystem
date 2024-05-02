package net.nicovrc.dev;

public class VideoData {

    private String VideoID;
    private long VideoCreateTime;
    private String VideoHost;
    private String m3u8;
    private String ImageURL;

    public String getVideoID() {
        return VideoID;
    }

    public void setVideoID(String videoID) {
        VideoID = videoID;
    }

    public long getVideoCreateTime() {
        return VideoCreateTime;
    }

    public void setVideoCreateTime(long videoCreateTime) {
        VideoCreateTime = videoCreateTime;
    }

    public String getVideoHost() {
        return VideoHost;
    }

    public void setVideoHost(String videoHost) {
        VideoHost = videoHost;
    }

    public String getM3u8() {
        return m3u8;
    }

    public void setM3u8(String m3u8) {
        this.m3u8 = m3u8;
    }

    public String getImageURL() {
        return ImageURL;
    }

    public void setImageURL(String imageURL) {
        ImageURL = imageURL;
    }
}
