import java.util.Objects;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.Thread;

public class Main {

    public static void main(String[] args) throws Exception {
        new Thread(() -> {
            try {
                System.out.println("Starting variable tests");
                new VariableTests().run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
        new Thread(() -> {
            try {
                System.out.println("Starting span tests");
                new SpanTests().run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
