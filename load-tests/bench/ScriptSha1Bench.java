// Contention bench for the Gateway rate limiter's script object (docs/testing.md,
// "Day 9: the Gateway's own rate-limiter script"). NOT part of any build.
//
// It runs INSIDE the real Gateway boot jar, through Spring Boot's own launcher, so the
// upstream script resolves through the same nested-jar URL as in production
// (jar:nested:...!BOOT-INF/lib/spring-cloud-gateway-server-webflux-*.jar!/META-INF/...).
//
//   (from the repo root)
//   (cd api-gateway && ./mvnw -q -DskipTests package)
//   mkdir -p /tmp/gwcp /tmp/benchcls && unzip -q -o api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar -d /tmp/gwcp
//   javac -d /tmp/benchcls -cp "$(ls /tmp/gwcp/BOOT-INF/lib/*.jar | tr '\n' ':')/tmp/gwcp/BOOT-INF/classes" load-tests/bench/ScriptSha1Bench.java
//   java -Dloader.main=ScriptSha1Bench -Dloader.path=/tmp/benchcls -cp api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar \
//        org.springframework.boot.loader.launch.PropertiesLauncher before 5 8      # then: after 5 8
//
// (On Windows use ';' as the classpath separator.) Exceptions per call: add
// -XX:StartFlightRecording=filename=x.jfr and read jdk.ExceptionStatistics (throwables) with `jfr print`.
import com.flashmart.gateway.ratelimit.PreloadedRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.LongAdder;

/**
 * 8 threads call getSha1() as fast as they can for N seconds. Runs inside the real
 * Gateway boot jar (PropertiesLauncher), so the script resolves through the same
 * nested-jar URL as in production. args: before|after seconds threads
 */
public class ScriptSha1Bench {
    public static void main(String[] a) throws Exception {
        String mode = a[0]; int seconds = Integer.parseInt(a[1]); int threads = Integer.parseInt(a[2]);
        RedisScript<?> script;
        if (mode.equals("before")) {   // exactly as GatewayRedisAutoConfiguration.redisRequestRateLimiterScript()
            DefaultRedisScript<List> s = new DefaultRedisScript<>();
            s.setScriptSource(new ResourceScriptSource(new ClassPathResource("META-INF/scripts/request_rate_limiter.lua")));
            s.setResultType(List.class);
            script = s;
            System.out.println("script URL: " + new ClassPathResource("META-INF/scripts/request_rate_limiter.lua").getURL());
        } else {
            script = PreloadedRedisScript.fromClasspath("ratelimit/request_rate_limiter.lua", List.class);
            System.out.println("script URL: " + new ClassPathResource("ratelimit/request_rate_limiter.lua").getURL());
        }
        System.out.println("mode=" + mode + " class=" + script.getClass().getSimpleName() + " sha1=" + script.getSha1());
        LongAdder calls = new LongAdder();
        CountDownLatch go = new CountDownLatch(1);
        long[] end = new long[1];
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                while (System.nanoTime() < end[0]) { script.getSha1(); calls.increment(); }
            });
            ts[i].start();
        }
        long t0 = System.nanoTime(); end[0] = t0 + seconds * 1_000_000_000L; go.countDown();
        for (Thread t : ts) t.join();
        double el = (System.nanoTime() - t0) / 1e9;
        long n = calls.sum();
        System.out.printf("RESULT mode=%s threads=%d seconds=%.2f calls=%d calls/s=%.0f mean_us_per_call_per_thread=%.3f%n",
                mode, threads, el, n, n / el, el * 1e6 * threads / n);
    }
}
