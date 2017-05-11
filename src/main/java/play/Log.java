package play;

import static org.fusesource.jansi.Ansi.ansi;

/**
 * Created by lilei on 2017/5/11.
 */
public class Log {

    private String clazz;

    private Log(String clazz) {
        this.clazz = clazz;
    }

    public static Log getLog(Class clazz) {
        return new Log(clazz.getName());
    }

    public void i(String msg) {
        String text = "@|green " + Thread.currentThread().getName() + "~INFO~" + clazz + "~" + msg + "|@";
        System.out.println(ansi().eraseScreen().render(text));
    }

}
