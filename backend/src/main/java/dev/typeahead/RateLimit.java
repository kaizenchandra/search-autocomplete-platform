package dev.typeahead;
import org.springframework.beans.factory.annotation.Value;import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.IOException;import org.springframework.stereotype.Component;
/** Fixed global token bucket: bounded memory; ingress supplies per-principal limits in production. */
@Component class RateLimit implements Filter {
 @Value("${rate-limit.rps:200}") double rps=200;
 @Value("${rate-limit.burst:500}") double burst=500;
 private double tokens=500;private long last=System.nanoTime();
 private synchronized boolean allow(){long now=System.nanoTime();tokens=Math.min(burst,tokens+(now-last)/1e9*rps);last=now;if(tokens<1)return false;tokens--;return true;}
 public void doFilter(ServletRequest req,ServletResponse res,FilterChain chain)throws IOException,ServletException{var request=(HttpServletRequest)req;var response=(HttpServletResponse)res;if(request.getRequestURI().startsWith("/api/")&&!allow()){response.setStatus(429);response.setHeader("Retry-After","1");response.setContentType("application/problem+json");response.getWriter().write("{\"title\":\"Rate limit exceeded\",\"status\":429}");return;}chain.doFilter(req,res);}
}
