package net.fjordomatic.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How Docker Compose started a container — the project it belongs to, the service it is within that
 * project, the compose files it was defined by, and the directory compose ran in. Docker records all
 * four on the container itself as labels, so a compose-managed container carries its own address.
 *
 * <p>A container has coordinates or it has none: one started with plain {@code docker run} carries no
 * compose labels at all, and Fjord then does not know how it was started.
 *
 * <p><b>Labels are metadata a container sets about itself, so they are untrusted input.</b> The
 * factory is the whole of the validation, and it is deliberately unforgiving: names must look like
 * compose names, every path must be absolute and free of anything a shell reads as syntax, and
 * anything else at all yields no coordinates. Fjord would rather know nothing about a container than
 * carry a hostile string towards a command line.
 *
 * @param project     the compose project name
 * @param service     the service's name within that project
 * @param configFiles every {@code -f} file the project was composed from, in the order compose
 *                    recorded them. A multi-file project needs all of them: recreating a service from
 *                    the first file alone drops whatever its override files said.
 * @param workingDir  the directory compose ran in, or null when the container does not report one
 */
public record ComposeCoordinates(
        String project,
        String service,
        List<String> configFiles,
        String workingDir
) {

    private static final String PROJECT_LABEL = "com.docker.compose.project";
    private static final String SERVICE_LABEL = "com.docker.compose.service";
    private static final String CONFIG_FILES_LABEL = "com.docker.compose.project.config_files";
    private static final String WORKING_DIR_LABEL = "com.docker.compose.project.working_dir";

    /** Compose's own alphabet for project and service names, which contains no shell syntax at all. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * Characters no real compose-file path contains and every one of which changes what a shell does.
     * A space is deliberately absent — a directory with a space in it is somebody's ordinary setup,
     * and refusing it would cost them the action for no gain once the path is quoted.
     */
    private static final Pattern SHELL_SYNTAX = Pattern.compile("[;&|<>$`\"'\\\\*?()\\[\\]{}!#\\n\\r\\t\\x00]");

    public ComposeCoordinates {
        configFiles = List.copyOf(configFiles);
    }

    /**
     * The coordinates a container's labels state, or empty when it has none Fjord will act on — either
     * because it is not compose-managed, or because what it says about itself does not survive
     * validation.
     */
    public static Optional<ComposeCoordinates> fromLabels(Map<String, String> labels) {
        if (labels == null) {
            return Optional.empty();
        }
        String project = labels.get(PROJECT_LABEL);
        String service = labels.get(SERVICE_LABEL);
        String configFiles = labels.get(CONFIG_FILES_LABEL);
        String workingDir = labels.get(WORKING_DIR_LABEL);

        if (!isComposeName(project) || !isComposeName(service) || configFiles == null || configFiles.isBlank()) {
            return Optional.empty();
        }

        List<String> files = new ArrayList<>();
        for (String entry : configFiles.split(",", -1)) {
            String path = entry.trim();
            if (!isSafeAbsolutePath(path)) {
                return Optional.empty();
            }
            files.add(path);
        }

        String directory = workingDir == null ? null : workingDir.trim();
        if (directory != null && !isSafeAbsolutePath(directory)) {
            return Optional.empty();
        }

        return Optional.of(new ComposeCoordinates(project, service, files, directory));
    }

    private static boolean isComposeName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    private static boolean isSafeAbsolutePath(String path) {
        return path != null
            && path.startsWith("/")
            && !SHELL_SYNTAX.matcher(path).find();
    }
}
