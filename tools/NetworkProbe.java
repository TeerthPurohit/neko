import java.net.URI;
public class NetworkProbe {
    public static void main(String[] args) {
        for(String url: args) try {
            var c=(javax.net.ssl.HttpsURLConnection)URI.create(url).toURL().openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
            System.out.println(URI.create(url).getHost()+": "+c.getResponseCode()); c.disconnect();
        } catch(Exception e) { System.out.println(e.getClass().getSimpleName()+": "+e.getMessage()); }
    }
}
