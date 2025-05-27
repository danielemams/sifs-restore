package org.infinispan;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

public class ZeroSecurityHostnameVerifier implements HostnameVerifier {
   @Override
   public boolean verify(String hostname, SSLSession session) {
      return true;
   }
}
