package xyz.n7mn;

import java.util.Date;

public class LogData {

    private String LogId;
    private long Time;
    private String HTTPRequest;
    private String RequestURL;
    private String ErrorMessage;

    public LogData(String logId, long time, String HTTPRequest, String requestURL, String errorMessage){
        this.LogId = logId;
        this.Time = time;
        this.HTTPRequest = HTTPRequest;
        this.RequestURL = requestURL;
        this.ErrorMessage = errorMessage;
    }

    public String getLogId() {
        return LogId;
    }

    public void setLogId(String logId) {
        LogId = logId;
    }

    public long getTime() {
        return Time;
    }

    public void setTime(long time) {
        Time = time;
    }

    public String getHTTPRequest() {
        return HTTPRequest;
    }

    public void setHTTPRequest(String HTTPRequest) {
        this.HTTPRequest = HTTPRequest;
    }

    public String getRequestURL() {
        return RequestURL;
    }

    public void setRequestURL(String requestURL) {
        RequestURL = requestURL;
    }

    public String getErrorMessage() {
        return ErrorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        ErrorMessage = errorMessage;
    }
}
