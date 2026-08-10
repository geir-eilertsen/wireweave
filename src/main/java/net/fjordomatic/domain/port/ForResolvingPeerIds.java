package net.fjordomatic.domain.port;

/**
 * Which peer answers on a tunnel address.
 *
 * <p>Answers with the peer's <b>id</b> — its immutable WireGuard config directory — and never with its
 * display name. It was called {@code ForResolvingPeerNames} and returned the same value under the name
 * "peer name", which is the naming lie this whole identity refactor kept tripping over: a caller who
 * believes it holds a name will happily compare it to one, and a caller who believes it holds an id will
 * key on it. Only the second is safe, so the port says so.
 *
 * <p>Falls back to the address itself when no peer bears it — the caller sees a value it can print, and
 * one it can tell apart from an id.
 */
public interface ForResolvingPeerIds {
    String resolvePeerIdByIp(String ipAddress);
}
