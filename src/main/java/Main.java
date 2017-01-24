import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.net.ssl.*;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public class Main extends HttpServlet {

  private JedisPool pool;

  public Main() throws URISyntaxException, KeyManagementException, NoSuchAlgorithmException, IOException {
    if(System.getenv("REDIS_URL") == null) {
      throw new IllegalArgumentException("No REDIS_URL is set!");
    }

    String rawRedisUrl = System.getenv("REDIS_URL");

    String[] redisUrlParts = rawRedisUrl.split(":");

    Integer port = Integer.valueOf(redisUrlParts[redisUrlParts.length-1]);

    Integer sslPort = port + 1;

    redisUrlParts[0] = "rediss";
    redisUrlParts[redisUrlParts.length-1] = String.valueOf(sslPort);

    String redissUrl = String.join(":", redisUrlParts);

    URI redisUri = new URI(redissUrl);

    final SSLParameters sslParameters = new SSLParameters();
    sslParameters.setEndpointIdentificationAlgorithm("HTTPS");

    pool = new JedisPool(redisUri, createWeakSslSocketFactory(), sslParameters, createWeakHostnameVerifier());
  }

  public SSLSocketFactory createWeakSslSocketFactory() throws NoSuchAlgorithmException, KeyManagementException {
    // Create a trust manager that does not validate certificate chains
    TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }

      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException { }
    }};

    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, trustAllCerts, new SecureRandom());
    return sslContext.getSocketFactory();
  }

  public HostnameVerifier createWeakHostnameVerifier() {
    // Ignore differences between given hostname and certificate hostname
    return new HostnameVerifier() {
      @Override
      public boolean verify(String hostname, SSLSession session) {
        return true;
      }
    };
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    String path = req.getContextPath();
    Long sessionLength = System.currentTimeMillis() - req.getSession().getCreationTime();

    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(path.getBytes(StandardCharsets.UTF_8));
      String key = new String(hash);

      try (Jedis jedis = pool.getResource()) {
        System.out.println("Updating Redis: " + path);
        jedis.hset("routes", path, key);
        jedis.hincrBy(key, "time", sessionLength);
        jedis.hincrBy(key, "count", 1);

        Integer sum = Integer.valueOf(jedis.hget(key, "time"));
        Integer count = Integer.valueOf(jedis.hget(key, "count"));
        Float avg = sum / Float.valueOf(count);
        jedis.hset(key, "average", String.valueOf(avg));

        resp.getWriter().print("SUM=" + sum + " COUNT=" + count + " AVG=" + avg);
      }
    } catch (NoSuchAlgorithmException e) {
      resp.getWriter().print("Error!");
      e.printStackTrace();
    }
  }

  public static void main(String[] args) throws Exception {
    Server server = new Server(Integer.valueOf(System.getenv("PORT")));
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    server.setHandler(context);
    context.addServlet(new ServletHolder(new Main()),"/*");
    server.start();
    server.join();
  }
}
