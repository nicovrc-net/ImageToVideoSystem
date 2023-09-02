package xyz.n7mn;

public class FileNotSupportException extends Exception {

    private final String Message;

    public FileNotSupportException(String msg){
        this.Message = msg;
    }

    @Override
    public String getMessage() {
        return Message;
    }
}
