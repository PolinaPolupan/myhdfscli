package ru.mirea.hadoop.myhdfscli;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Scanner;
import java.util.stream.Collectors;

public class MyHdfsCli {

    private static String webhdfs_url;
    private static String username;

    private static String current_hdfs_dir = "/";
    private static Path current_local_dir =
            Path.of(System.getProperty("user.dir"));

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {

        if (args.length != 3) {
            System.err.println(
                    "Usage: MyHdfsCli <server> <port> <username>"
            );
            return;
        }

        String server = args[0];
        String port = args[1];
        username = args[2];

        webhdfs_url =
                "http://" + server + ":" + port + "/webhdfs/v1";

        run_cli();
    }

    private static void run_cli() throws Exception {

        Scanner scanner = new Scanner(System.in);

        while (true) {

            System.out.print("HDFS> ");

            String line = scanner.nextLine().trim();

            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\\s+");

            String command = parts[0];

            try {
                switch (command) {

                    case "mkdir" -> make_directory(parts);

                    case "put" -> upload(parts);

                    case "get" -> download(parts);

                    case "append" -> append(parts);

                    case "delete" -> delete(parts);

                    case "ls" -> list_hdfs();

                    case "cd" -> change_hdfs_directory(parts);

                    case "lls" -> list_local();

                    case "lcd" -> change_local_directory(parts);

                    case "exit", "quit" -> {
                        return;
                    }

                    case "help" -> print_usage();

                    default ->
                            System.out.println(
                                    "Unknown command: " + command
                            );
                }

            } catch (Exception e) {
                System.err.println(
                        "Error: " + e.getMessage()
                );
            }
        }
    }

    /*
     * Build URL for a WebHDFS operation.
     */
    private static String build_url(
            String path,
            String operation
    ) {

        return webhdfs_url
                + encode_path(path)
                + "?op=" + operation
                + "&user.name="
                + URLEncoder.encode(
                        username,
                        StandardCharsets.UTF_8
                );
    }

    /*
     * Encode every component of the HDFS path.
     */
    private static String encode_path(String path) {

        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(
                    "HDFS path must not be empty"
            );
        }

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        return Arrays.stream(
                        path.split("/", -1)
                )
                .map(part ->
                        URLEncoder.encode(
                                part,
                                StandardCharsets.UTF_8
                        ).replace("+", "%20")
                )
                .collect(
                        Collectors.joining("/")
                );
    }

    /*
     * Convert a name entered by the user into
     * a path relative to the current HDFS directory.
     */
    private static String hdfs_path(String name) {

        if (name.equals("..")) {
            return parent_directory(current_hdfs_dir);
        }

        if (name.startsWith("/")) {
            return name;
        }

        if (current_hdfs_dir.equals("/")) {
            return "/" + name;
        }

        return current_hdfs_dir + "/" + name;
    }

    /*
     * Return parent directory.
     */
    private static String parent_directory(String path) {

        if (path.equals("/")) {
            return "/";
        }

        int last_slash = path.lastIndexOf('/');

        if (last_slash <= 0) {
            return "/";
        }

        return path.substring(0, last_slash);
    }

    /*
     * mkdir <name>
     */
    private static void make_directory(
            String[] parts
    ) throws Exception {

        if (parts.length != 2) {
            System.out.println(
                    "Usage: mkdir <directory>"
            );
            return;
        }

        String path = hdfs_path(parts[1]);

        String url = build_url(
                path,
                "MKDIRS"
        );

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .PUT(
                                HttpRequest.BodyPublishers.noBody()
                        )
                        .build();

        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() >= 200
                && response.statusCode() < 300) {

            System.out.println(
                    "Directory created: " + path
            );

        } else {

            System.err.println(
                    "MKDIRS failed: HTTP "
                            + response.statusCode()
            );

            System.err.println(response.body());
        }
    }

    /*
     * ls
     */
    private static void list_hdfs()
            throws Exception {

        String url = build_url(
                current_hdfs_dir,
                "LISTSTATUS"
        );

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() != 200) {

            throw new IOException(
                    "LISTSTATUS failed: HTTP "
                            + response.statusCode()
            );
        }

        System.out.println(
                response.body()
        );
    }

    /*
     * cd <directory>
     */
    private static void change_hdfs_directory(
            String[] parts
    ) {

        if (parts.length != 2) {
            System.out.println(
                    "Usage: cd <directory>"
            );
            return;
        }

        String new_path = hdfs_path(parts[1]);

        current_hdfs_dir = normalize_path(new_path);

        System.out.println(
                "HDFS directory: "
                        + current_hdfs_dir
        );
    }

    /*
     * Normalize paths such as /a/../b.
     */
    private static String normalize_path(
            String path
    ) {

        String[] parts = path.split("/");

        java.util.ArrayDeque<String> stack =
                new java.util.ArrayDeque<>();

        for (String part : parts) {

            if (part.isEmpty() || part.equals(".")) {
                continue;
            }

            if (part.equals("..")) {

                if (!stack.isEmpty()) {
                    stack.removeLast();
                }

            } else {
                stack.addLast(part);
            }
        }

        if (stack.isEmpty()) {
            return "/";
        }

        return "/" + String.join("/", stack);
    }

    /*
     * lls
     */
    private static void list_local() {

        try {

            Files.list(current_local_dir)
                    .sorted()
                    .forEach(path -> {

                        if (Files.isDirectory(path)) {
                            System.out.println(
                                    "[DIR]  "
                                            + path.getFileName()
                            );
                        } else {
                            System.out.println(
                                    "[FILE] "
                                            + path.getFileName()
                            );
                        }
                    });

        } catch (IOException e) {

            System.err.println(
                    "Cannot list local directory: "
                            + e.getMessage()
            );
        }
    }

    /*
     * lcd <directory>
     */
    private static void change_local_directory(
            String[] parts
    ) {

        if (parts.length != 2) {
            System.out.println(
                    "Usage: lcd <directory>"
            );
            return;
        }

        Path new_path;

        if (parts[1].equals("..")) {

            Path parent =
                    current_local_dir.getParent();

            if (parent == null) {
                return;
            }

            new_path = parent;

        } else {

            new_path =
                    current_local_dir.resolve(parts[1]);
        }

        new_path = new_path.normalize();

        if (!Files.isDirectory(new_path)) {

            System.err.println(
                    "Local directory does not exist: "
                            + new_path
            );

            return;
        }

        current_local_dir = new_path;

        System.out.println(
                "Local directory: "
                        + current_local_dir
        );
    }

    /*
     * put <local-file>
     */
    private static void upload(
            String[] parts
    ) throws Exception {

        if (parts.length != 2) {
            System.out.println(
                    "Usage: put <local-file>"
            );
            return;
        }

        Path local_path =
                current_local_dir.resolve(parts[1]);

        if (!Files.isRegularFile(local_path)) {

            throw new IOException(
                    "Local file not found: "
                            + local_path
            );
        }

        String hdfs_file =
                hdfs_path(parts[1]);

        String url =
                build_url(
                        hdfs_file,
                        "CREATE"
                ) + "&overwrite=true";

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .PUT(
                                HttpRequest.BodyPublishers.noBody()
                        )
                        .build();

        HttpResponse<Void> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.discarding()
                );

        if (response.statusCode() != 307) {

            throw new IOException(
                    "Expected HTTP 307 redirect, got "
                            + response.statusCode()
            );
        }

        URI redirect_uri =
                response.headers()
                        .firstValue("Location")
                        .map(URI::create)
                        .orElseThrow(
                                () -> new IOException(
                                        "Missing redirect Location"
                                )
                        );

        HttpRequest upload_request =
                HttpRequest.newBuilder()
                        .uri(redirect_uri)
                        .PUT(
                                HttpRequest.BodyPublishers
                                        .ofFile(local_path)
                        )
                        .build();

        HttpResponse<String> upload_response =
                HTTP_CLIENT.send(
                        upload_request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (upload_response.statusCode() >= 200
                && upload_response.statusCode() < 300) {

            System.out.println(
                    "Uploaded: " + hdfs_file
            );

        } else {

            throw new IOException(
                    "Upload failed: HTTP "
                            + upload_response.statusCode()
            );
        }
    }

    /*
     * get <file>
     */
    private static void download(
            String[] parts
    ) throws Exception {

        if (parts.length != 2) {
            System.out.println(
                    "Usage: get <file>"
            );
            return;
        }

        String hdfs_file =
                hdfs_path(parts[1]);

        Path local_file =
                current_local_dir.resolve(parts[1]);

        String url =
                build_url(
                        hdfs_file,
                        "OPEN"
                );

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

        HttpResponse<Void> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.discarding()
                );

        if (response.statusCode() != 307) {

            throw new IOException(
                    "Expected HTTP 307 redirect, got "
                            + response.statusCode()
            );
        }

        URI redirect_uri =
                response.headers()
                        .firstValue("Location")
                        .map(URI::create)
                        .orElseThrow(
                                () -> new IOException(
                                        "Missing redirect Location"
                                )
                        );

        HttpRequest download_request =
                HttpRequest.newBuilder()
                        .uri(redirect_uri)
                        .GET()
                        .build();

        HttpResponse<byte[]> download_response =
                HTTP_CLIENT.send(
                        download_request,
                        HttpResponse.BodyHandlers.ofByteArray()
                );

        if (download_response.statusCode() != 200) {

            throw new IOException(
                    "Download failed: HTTP "
                            + download_response.statusCode()
            );
        }

        Files.write(
                local_file,
                download_response.body()
        );

        System.out.println(
                "Downloaded: " + local_file
        );
    }

    /*
     * delete <file>
     */
    private static void delete(
            String[] parts
    ) throws Exception {

        if (parts.length != 2) {
            System.out.println(
                    "Usage: delete <file>"
            );
            return;
        }

        String hdfs_file =
                hdfs_path(parts[1]);

        String url =
                build_url(
                        hdfs_file,
                        "DELETE"
                ) + "&recursive=false";

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .DELETE()
                        .build();

        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() >= 200
                && response.statusCode() < 300) {

            System.out.println(
                    "Deleted: " + hdfs_file
            );

        } else {

            throw new IOException(
                    "Delete failed: HTTP "
                            + response.statusCode()
            );
        }
    }

    /*
     * append <local-file> <hdfs-file>
     */
    private static void append(
            String[] parts
    ) throws Exception {

        if (parts.length != 3) {
            System.out.println(
                    "Usage: append <local-file> <hdfs-file>"
            );
            return;
        }

        Path local_file =
                current_local_dir.resolve(parts[1]);

        if (!Files.isRegularFile(local_file)) {

            throw new IOException(
                    "Local file not found: "
                            + local_file
            );
        }

        String hdfs_file =
                hdfs_path(parts[2]);

        String url =
                build_url(
                        hdfs_file,
                        "APPEND"
                );

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .POST(
                                HttpRequest.BodyPublishers.noBody()
                        )
                        .build();

        HttpResponse<Void> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.discarding()
                );

        if (response.statusCode() != 307) {

            throw new IOException(
                    "Expected HTTP 307 redirect, got "
                            + response.statusCode()
            );
        }

        URI redirect_uri =
                response.headers()
                        .firstValue("Location")
                        .map(URI::create)
                        .orElseThrow(
                                () -> new IOException(
                                        "Missing redirect Location"
                                )
                        );

        HttpRequest append_request =
                HttpRequest.newBuilder()
                        .uri(redirect_uri)
                        .POST(
                                HttpRequest.BodyPublishers
                                        .ofFile(local_file)
                        )
                        .build();

        HttpResponse<String> append_response =
                HTTP_CLIENT.send(
                        append_request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (append_response.statusCode() >= 200
                && append_response.statusCode() < 300) {

            System.out.println(
                    "Appended " + local_file
                            + " to " + hdfs_file
            );

        } else {

            throw new IOException(
                    "Append failed: HTTP "
                            + append_response.statusCode()
            );
        }
    }

    private static void print_usage() {

        System.out.println();
        System.out.println("Commands:");
        System.out.println(
                "  mkdir <directory>"
        );
        System.out.println(
                "  put <local-file>"
        );
        System.out.println(
                "  get <hdfs-file>"
        );
        System.out.println(
                "  append <local-file> <hdfs-file>"
        );
        System.out.println(
                "  delete <hdfs-file>"
        );
        System.out.println(
                "  ls"
        );
        System.out.println(
                "  cd <directory>"
        );
        System.out.println(
                "  lls"
        );
        System.out.println(
                "  lcd <directory>"
        );
        System.out.println(
                "  exit"
        );
        System.out.println();
    }
}
