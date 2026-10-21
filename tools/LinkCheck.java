import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks every link in the docs site: relative links to a file that exists, fragments to a heading that exists, and
 * (unless {@code --offline}) every external URL answering below 400.
 *
 * <pre>
 * java tools/LinkCheck.java docs              # everything
 * java tools/LinkCheck.java docs --offline    # files and headings only
 * </pre>
 *
 * <p>JDK only and one file, so it runs from a checkout with nothing installed, in CI and by hand alike. It exists
 * because the developer guide pointed at a branch that does not exist and at a build script that had moved, and
 * nothing noticed: a guide that is the one place to start has to be checked like code.
 *
 * <p>Fragments are matched against GitHub-style heading ids (lower case, punctuation dropped, spaces to hyphens),
 * which is what GitHub Pages' GFM renderer writes. Liquid ({@code {{ ... }}}) is skipped: the layout's links only
 * exist once Jekyll has run.
 */
public final class LinkCheck {

    private static final Pattern MD_LINK = Pattern.compile("!?\\[[^\\]]*\\]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)");
    private static final Pattern HTML_LINK = Pattern.compile("(?:href|src)=\"([^\"]+)\"");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");

    /** Hosts and placeholders that are examples in the text, not links anyone follows. */
    private static final List<String> EXAMPLES = List.of("localhost", "example.com", "example.org",
            "github.com/you/", "githubusercontent.com/you/");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: java tools/LinkCheck.java <docs folder> [--offline]");
            System.exit(2);
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        boolean offline = List.of(args).contains("--offline");

        List<Path> pages;
        try (Stream<Path> walk = Files.walk(root)) {
            pages = walk.filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".html")).sorted().toList();
        }

        List<String> problems = new ArrayList<>();
        Map<String, List<String>> external = new TreeMap<>();
        for (Path page : pages) {
            String where = root.relativize(page).toString().replace('\\', '/');
            for (String link : links(page)) {
                if (link.contains("{{") || link.contains("{%") || link.startsWith("mailto:")) continue;
                if (link.startsWith("http://") || link.startsWith("https://")) {
                    if (EXAMPLES.stream().noneMatch(link::contains)) {
                        external.computeIfAbsent(link.split("#", 2)[0], k -> new ArrayList<>()).add(where);
                    }
                    continue;
                }
                String problem = checkLocal(page, link);
                if (problem != null) problems.add(where + ": " + link + " -- " + problem);
            }
        }

        if (!offline) {
            problems.addAll(checkExternal(external));
        }

        problems.forEach(System.out::println);
        System.out.println(pages.size() + " pages, " + external.size() + " external URLs"
                + (offline ? " (not checked)" : "") + ", " + problems.size() + " broken");
        System.exit(problems.isEmpty() ? 0 : 1);
    }

    private static List<String> links(Path page) throws IOException {
        List<String> found = new ArrayList<>();
        boolean fenced = false;
        Pattern pattern = page.toString().endsWith(".html") ? HTML_LINK : MD_LINK;
        for (String line : Files.readAllLines(page)) {
            if (FENCE.matcher(line).find()) {
                fenced = !fenced;
                continue;
            }
            if (fenced) continue;
            Matcher m = pattern.matcher(line);
            while (m.find()) found.add(m.group(1));
            if (pattern == MD_LINK) {
                Matcher h = HTML_LINK.matcher(line);
                while (h.find()) found.add(h.group(1));
            }
        }
        return found;
    }

    /** Null when the link resolves; otherwise what is wrong with it. */
    private static String checkLocal(Path page, String link) throws IOException {
        String[] parts = link.split("#", 2);
        String file = parts[0];
        String fragment = parts.length > 1 ? parts[1] : null;

        Path target = file.isEmpty() ? page : page.getParent().resolve(file).normalize();
        if (Files.isDirectory(target)) {
            target = Stream.of("index.md", "README.md", "index.html").map(target::resolve)
                    .filter(Files::isRegularFile).findFirst().orElse(null);
            if (target == null) return "a folder with no index.md or README.md";
        } else if (!Files.isRegularFile(target)) {
            // Jekyll turns page.md into page.html, so a link to the .html of a Markdown page is a link to it.
            Path md = target.resolveSibling(target.getFileName().toString().replaceFirst("\\.html$", ".md"));
            if (!target.toString().endsWith(".html") || !Files.isRegularFile(md)) return "no such file";
            target = md;
        }
        if (fragment == null || fragment.isEmpty() || !target.toString().endsWith(".md")) return null;
        return ids(target).contains(fragment) ? null : "no heading with that id in " + target.getFileName();
    }

    private static Set<String> ids(Path markdown) throws IOException {
        Set<String> ids = new HashSet<>();
        Map<String, Integer> seen = new TreeMap<>();
        boolean fenced = false;
        for (String line : Files.readAllLines(markdown)) {
            if (FENCE.matcher(line).find()) {
                fenced = !fenced;
                continue;
            }
            Matcher m = HEADING.matcher(line);
            if (fenced || !m.matches()) continue;
            String text = m.group(1).replaceAll("`|\\*\\*|\\*|_(?=\\w)|(?<=\\w)_", "")
                    .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
            String id = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} _-]", "").replace(' ', '-');
            int n = seen.merge(id, 1, Integer::sum);
            ids.add(n == 1 ? id : id + "-" + (n - 1));
        }
        return ids;
    }

    private static List<String> checkExternal(Map<String, List<String>> urls) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        String token = System.getenv("GITHUB_TOKEN");
        List<CompletableFuture<String>> checks = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : urls.entrySet()) {
            String url = entry.getKey();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "vexelray-docs-linkcheck").GET();
            if (token != null && url.startsWith("https://github.com/")) request.header("Authorization", "Bearer " + token);
            checks.add(client.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
                    .handle((response, failure) -> {
                        if (failure != null) return problem(url, entry.getValue(), failure.getClass().getSimpleName());
                        int status = response.statusCode();
                        // 429 and 403 are a rate limit, not an answer about the page.
                        return status < 400 || status == 429 || status == 403 ? null
                                : problem(url, entry.getValue(), "HTTP " + status);
                    }));
        }
        return checks.stream().map(CompletableFuture::join).filter(p -> p != null).toList();
    }

    private static String problem(String url, List<String> pages, String why) {
        return String.join(", ", new TreeMap<>(Map.of(pages.get(0), 0)).keySet())
                + (pages.size() > 1 ? " (+" + (pages.size() - 1) + " more)" : "") + ": " + url + " -- " + why;
    }
}
