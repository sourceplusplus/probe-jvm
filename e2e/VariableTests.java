import java.util.Objects;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.Thread;

public class VariableTests {

    public static void main(String[] args) throws Exception {
        ServerSocket serverSocket = new ServerSocket(4000);
        while (true) {
            Socket socket = serverSocket.accept();
            new Thread(() -> {
                doTest();
                try {
                    socket.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    public static void doTest() {
        int a = 1;
        char b = 'a';
        String c = "a";
        boolean d = true;
        double e = 1.0;
        float f = 1.0f;
        long g = 1L;
        short h = 1;
        byte i = 1;

        System.out.println(Objects.hash(a, b, c, d, e, f, g, h, i));
    }
}
