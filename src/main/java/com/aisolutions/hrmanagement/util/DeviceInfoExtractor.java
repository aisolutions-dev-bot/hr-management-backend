package com.aisolutions.hrmanagement.util;

import java.net.InetAddress;

import com.aisolutions.hrmanagement.service.useractionlog.UserActionLogService.DeviceInfo;

import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.core.HttpHeaders;

public class DeviceInfoExtractor {

  /** Build DeviceInfo from HTTP headers and the Vert.x request. */
  public static DeviceInfo extract(HttpHeaders headers, HttpServerRequest request) {
    String userAgent = headers != null ? headers.getHeaderString("User-Agent") : null;
    String ipAddress = extractIpAddress(headers, request);
    String deviceName = getPcNameFromIp(ipAddress);
    if (deviceName == null || deviceName.isBlank()) {
      deviceName = parseDeviceName(userAgent); // Fallback: "Chrome 120 / Windows 10"
    }

    return new DeviceInfo(deviceName, ipAddress, null);
  }

  /** Overload for when HttpServerRequest is not available. */
  public static DeviceInfo extract(HttpHeaders headers) {
    return extract(headers, null);
  }

  // Reverse-DNS the IP to a PC name; only resolves on an internal network with DNS.
  private static String getPcNameFromIp(String ipAddress) {
    if (ipAddress == null || ipAddress.isBlank()) {
      return null;
    }

    if ("127.0.0.1".equals(ipAddress) || "0:0:0:0:0:0:0:1".equals(ipAddress)) {
      return null;
    }

    try {
      InetAddress addr = InetAddress.getByName(ipAddress);
      String hostName = addr.getHostName();

      // No reverse DNS record: getHostName returns the IP unchanged.
      if (hostName.equals(ipAddress)) {
        return null;
      }

      // Strip the domain suffix: "JOHN-PC.company.local" -> "JOHN-PC".
      if (hostName.contains(".")) {
        hostName = hostName.substring(0, hostName.indexOf("."));
      }

      return truncate(hostName.toUpperCase(), 45);
    } catch (Exception e) {
      return null;
    }
  }

  // Prefer proxy headers, then the Vert.x remote address.
  private static String extractIpAddress(HttpHeaders headers, HttpServerRequest request) {
    if (headers != null) {
      String ip = headers.getHeaderString("X-Forwarded-For");
      if (ip != null && !ip.isBlank()) {
        return truncate(ip.split(",")[0].trim(), 25);
      }

      ip = headers.getHeaderString("X-Real-IP");
      if (ip != null && !ip.isBlank()) {
        return truncate(ip.trim(), 25);
      }
    }

    if (request != null && request.remoteAddress() != null) {
      String ip = request.remoteAddress().host();
      if (ip != null && !ip.isBlank()) {
        return truncate(ip, 25);
      }
    }

    return null;
  }

  // Parse the User-Agent into "{Browser} / {OS}" (max 45 chars).
  private static String parseDeviceName(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return "Unknown";
    }

    String browser = parseBrowser(userAgent);
    String os = parseOS(userAgent);
    String deviceType = parseDeviceType(userAgent);

    String deviceName;
    if ("Mobile".equals(deviceType) || "Tablet".equals(deviceType)) {
      deviceName = deviceType + " " + browser + " / " + os;
    } else {
      deviceName = browser + " / " + os;
    }

    return truncate(deviceName, 45);
  }

  private static String parseBrowser(String ua) {
    ua = ua.toLowerCase();

    if (ua.contains("edg/")) {
      return "Edge" + extractVersion(ua, "edg/");
    }
    if (ua.contains("opr/") || ua.contains("opera")) {
      return "Opera";
    }
    if (ua.contains("chrome/") && !ua.contains("chromium")) {
      return "Chrome" + extractVersion(ua, "chrome/");
    }
    if (ua.contains("firefox/")) {
      return "Firefox" + extractVersion(ua, "firefox/");
    }
    if (ua.contains("safari/") && !ua.contains("chrome")) {
      return "Safari";
    }
    if (ua.contains("msie") || ua.contains("trident/")) {
      return "IE";
    }

    return "Browser";
  }

  private static String parseOS(String ua) {
    ua = ua.toLowerCase();

    if (ua.contains("windows nt 10"))
      return "Windows 10";
    if (ua.contains("windows"))
      return "Windows";
    if (ua.contains("mac os x"))
      return "macOS";
    if (ua.contains("android"))
      return "Android";
    if (ua.contains("iphone"))
      return "iOS";
    if (ua.contains("ipad"))
      return "iPadOS";
    if (ua.contains("linux"))
      return "Linux";

    return "Unknown OS";
  }

  private static String parseDeviceType(String ua) {
    ua = ua.toLowerCase();

    if (ua.contains("mobile") || (ua.contains("android") && !ua.contains("tablet"))) {
      if (ua.contains("ipad"))
        return "Tablet";
      return "Mobile";
    }
    if (ua.contains("tablet") || ua.contains("ipad")) {
      return "Tablet";
    }

    return "Desktop";
  }

  private static String extractVersion(String ua, String prefix) {
    try {
      int start = ua.indexOf(prefix) + prefix.length();
      int end = start;

      while (end < ua.length() && (Character.isDigit(ua.charAt(end)) || ua.charAt(end) == '.')) {
        end++;
      }

      if (end > start) {
        String version = ua.substring(start, end);
        if (version.contains(".")) {
          version = version.substring(0, version.indexOf("."));
        }
        return " " + version;
      }
    } catch (Exception e) {
      // Ignore
    }
    return "";
  }

  private static String truncate(String value, int maxLength) {
    if (value == null)
      return null;
    if (value.length() <= maxLength)
      return value;
    return value.substring(0, maxLength - 3) + "...";
  }
}
