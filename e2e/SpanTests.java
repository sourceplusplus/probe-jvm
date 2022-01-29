import java.util.Objects;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.Thread;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;

public class SpanTests {

    public static void run() throws Exception {
        ServerSocket serverSocket = new ServerSocket(4001);
        while (true) {
            Socket socket = serverSocket.accept();
            new Thread(() -> {
                doTest();
                try {
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes());
                    socket.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    public static void doTest() {
        primitiveArgs(1, '2', "3", true, 4.0, 5.0f, 6L, (short) 7, (byte) 8);
        objectArgs(new Object(), new ComplexObject());
        collectionArgs(new Object[0], new int[0], new ArrayList<Integer>(), new ArrayList<ComplexObject>());
    }

    public static void primitiveArgs(int a, char b, String c, boolean d, double e, float f, long g, short h, byte i) {
        System.out.println(a);
        System.out.println(b);
        System.out.println(c);
        System.out.println(d);
        System.out.println(e);
        System.out.println(f);
        System.out.println(g);
        System.out.println(h);
        System.out.println(i);
    }

    public static void objectArgs(Object a, ComplexObject b) {
        System.out.println(a);
        System.out.println(b);
    }

    public static void collectionArgs(Object[] a, int[] b, List<Integer> c, Collection<ComplexObject> d) {
        System.out.println(a);
        System.out.println(b);
        System.out.println(c);
        System.out.println(d);
    }

    public static class ComplexObject {
        public int a = 1;
    }
}
