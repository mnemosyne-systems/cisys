/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Repository URL rules shared by project creation, branch listing and builds. A URL is accepted
 * only when it is an allowed HTTPS host and every address that host resolves to is public, unless
 * an administrator allowed the network explicitly.
 */
final class RepositoryUrls {
  static final String REQUIRED = "Repository URL is required";
  static final String SHAPE = "Use an HTTPS repository URL without credentials";
  static final String PORT = "The repository host must use an allowed port";
  static final String BLOCKED = "The repository host resolves to a blocked address";
  static final String UNKNOWN = "The repository host could not be resolved";

  /** Never fetched, even when the host is on the allowlist, unless the network is allowed too. */
  static final List<Cidr> BLOCKED_NETWORKS =
      parseNetworks(
          String.join(
              ",",
              "0.0.0.0/8",
              "10.0.0.0/8",
              "100.64.0.0/10",
              "127.0.0.0/8",
              "169.254.0.0/16",
              "172.16.0.0/12",
              "192.0.0.0/24",
              "192.0.2.0/24",
              "192.168.0.0/16",
              "198.18.0.0/15",
              "198.51.100.0/24",
              "203.0.113.0/24",
              "224.0.0.0/4",
              "240.0.0.0/4",
              "::/128",
              "::1/128",
              "fe80::/10",
              "fc00::/7",
              "ff00::/8",
              "2001:db8::/32"));

  private RepositoryUrls() {}

  /** Returns the canonical form used for project repository URLs. */
  static String normalize(String repositoryUrl) {
    if (repositoryUrl == null) return null;
    String trimmed = repositoryUrl.trim();
    URI uri = URI.create(trimmed);
    String host = uri.getHost();
    if (uri.getScheme() == null || host == null) return trimmed;
    String path = uri.getPath();
    if (path != null) {
      while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
      if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
      while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    }
    try {
      return new URI(
              uri.getScheme().toLowerCase(Locale.ROOT),
              uri.getUserInfo(),
              host.toLowerCase(Locale.ROOT),
              uri.getPort(),
              path,
              uri.getQuery(),
              uri.getFragment())
          .toString();
    } catch (java.net.URISyntaxException e) {
      throw new IllegalArgumentException(SHAPE, e);
    }
  }

  /** Adds the Git suffix at the clone/fetch boundary while preserving already suffixed URLs. */
  static String cloneUrl(String repositoryUrl) {
    String trimmed = repositoryUrl.replaceAll("/+$", "");
    return trimmed.endsWith(".git") ? trimmed : trimmed + ".git";
  }

  static void validate(
      String repositoryUrl,
      Set<String> allowedHosts,
      Set<Integer> allowedPorts,
      List<Cidr> allowedNetworks,
      HostResolver resolver) {
    if (repositoryUrl == null || repositoryUrl.isBlank() || repositoryUrl.length() > 2048) {
      throw new IllegalArgumentException(REQUIRED);
    }
    URI uri;
    try {
      uri = URI.create(repositoryUrl.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(SHAPE);
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getPath() == null
        || uri.getPath().isEmpty()
        || uri.getPath().equals("/")) {
      throw new IllegalArgumentException(SHAPE);
    }
    String host = host(uri);
    if (host == null) throw new IllegalArgumentException(SHAPE);
    if (!allowedPorts.contains(uri.getPort() == -1 ? 443 : uri.getPort()))
      throw new IllegalArgumentException(portMessage(allowedPorts));
    if (!allowedHosts.contains(host)) throw new IllegalArgumentException(hostMessage(allowedHosts));
    InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(UNKNOWN);
    }
    if (addresses == null || addresses.length == 0) throw new IllegalArgumentException(UNKNOWN);
    for (InetAddress address : addresses) {
      if (address == null) throw new IllegalArgumentException(UNKNOWN);
      byte[] raw = address.getAddress();
      byte[] bytes = unwrap(raw);
      boolean allowed = inside(bytes, allowedNetworks) || inside(raw, allowedNetworks);
      if (!allowed && inside(bytes, BLOCKED_NETWORKS)) throw new IllegalArgumentException(BLOCKED);
    }
  }

  static String hostMessage(Set<String> allowedHosts) {
    return allowedHosts.isEmpty()
        ? "Use a repository on an allowed host"
        : "Use a repository on an allowed host (" + String.join(", ", allowedHosts) + ")";
  }

  static String portMessage(Set<Integer> allowedPorts) {
    return allowedPorts.isEmpty()
        ? PORT
        : PORT
            + " ("
            + String.join(", ", allowedPorts.stream().map(String::valueOf).toList())
            + ")";
  }

  /** The lower-case host of an absolute URL, without IPv6 brackets, or null when there is none. */
  static String host(String repositoryUrl) {
    if (repositoryUrl == null) return null;
    try {
      return host(URI.create(repositoryUrl.trim()));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String host(URI uri) {
    String host = uri.getHost();
    if (host == null) return null;
    host = host.toLowerCase(Locale.ROOT);
    if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
    return host;
  }

  static Set<Integer> parsePorts(String value) {
    Set<Integer> ports = new LinkedHashSet<>();
    if (value != null) {
      for (String port : value.split(",")) {
        String trimmed = port.trim();
        if (trimmed.isEmpty()) continue;
        int number;
        try {
          number = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Invalid port: " + trimmed);
        }
        if (number < 1 || number > 65535)
          throw new IllegalArgumentException("Invalid port: " + trimmed);
        ports.add(number);
      }
    }
    return java.util.Collections.unmodifiableSet(ports);
  }

  static Set<String> parseHosts(String value) {
    Set<String> hosts = new LinkedHashSet<>();
    if (value != null) {
      for (String host : value.split(",")) {
        String trimmed = host.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.isEmpty()) hosts.add(trimmed);
      }
    }
    return java.util.Collections.unmodifiableSet(hosts);
  }

  static List<Cidr> parseNetworks(String value) {
    List<Cidr> networks = new ArrayList<>();
    if (value != null) {
      for (String network : value.split(",")) {
        String trimmed = network.trim();
        if (!trimmed.isEmpty()) networks.add(Cidr.parse(trimmed));
      }
    }
    return List.copyOf(networks);
  }

  /** Turns an IPv4-mapped IPv6 address, such as ::ffff:127.0.0.1, into its IPv4 form. */
  static byte[] unwrap(byte[] address) {
    if (address.length != 16) return address;
    for (int i = 0; i < 10; i++) if (address[i] != 0) return address;
    if ((address[10] & 0xFF) != 0xFF || (address[11] & 0xFF) != 0xFF) return address;
    return new byte[] {address[12], address[13], address[14], address[15]};
  }

  static boolean inside(byte[] address, List<Cidr> networks) {
    for (Cidr network : networks) if (network.contains(address)) return true;
    return false;
  }

  /** An IPv4 or IPv6 network in CIDR notation, for the allowlists. */
  record Cidr(byte[] network, int prefix) {
    static Cidr parse(String value) {
      int slash = value.indexOf('/');
      if (slash <= 0 || slash == value.length() - 1)
        throw new IllegalArgumentException("Invalid network: " + value);
      byte[] address;
      try {
        address = InetAddress.getByName(value.substring(0, slash)).getAddress();
      } catch (UnknownHostException e) {
        throw new IllegalArgumentException("Invalid network: " + value);
      }
      int prefix;
      try {
        prefix = Integer.parseInt(value.substring(slash + 1));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid network: " + value);
      }
      if (prefix < 0 || prefix > address.length * 8)
        throw new IllegalArgumentException("Invalid network: " + value);
      byte[] network = address.clone();
      for (int i = 0; i < network.length; i++) {
        int bits = prefix - i * 8;
        if (bits >= 8) continue;
        network[i] &= (byte) (bits <= 0 ? 0 : (0xFF << (8 - bits)) & 0xFF);
      }
      return new Cidr(network, prefix);
    }

    boolean contains(byte[] address) {
      if (address.length != network.length) return false;
      for (int i = 0; i < address.length; i++) {
        int bits = prefix - i * 8;
        if (bits <= 0) break;
        int mask = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
        if ((address[i] & mask) != (network[i] & mask)) return false;
      }
      return true;
    }
  }
}
