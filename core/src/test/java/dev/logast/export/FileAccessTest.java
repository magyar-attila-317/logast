package dev.logast.export;

import dev.logast.Fixtures;
import dev.logast.facts.Edge;
import dev.logast.facts.FactReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #172: files a service writes and reads: Azure Blob, SFTP (JSch, sshj, Spring Integration). */
class FileAccessTest {
    @TempDir
    Path tmp;

    private static void source(Path root, String path, String text) throws IOException {
        Path f = root.resolve("src/main/java/com/example/files").resolve(path);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    @Test
    void filesEachEntrypointWritesAndReads() throws IOException {
        Path svc = tmp.resolve("billing");
        Files.createDirectories(svc.resolve("src/main/resources"));
        Files.writeString(svc.resolve("src/main/resources/application.yml"), "export:\n  container: billing-exports\n");
        source(svc, "App.java", """
                package com.example.files;

                @org.springframework.boot.autoconfigure.SpringBootApplication
                public class App {
                }
                """);
        source(svc, "InvoiceExport.java", """
                package com.example.files;

                import com.azure.core.util.BinaryData;
                import com.azure.storage.blob.BlobContainerClient;
                import com.azure.storage.blob.BlobContainerClientBuilder;
                import com.azure.storage.blob.BlobServiceClient;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                class InvoiceExport {
                    private final BlobContainerClient container;

                    InvoiceExport(BlobServiceClient service, @Value("${export.container}") String containerName) {
                        this.container = service.getBlobContainerClient(containerName);
                    }

                    @Scheduled(cron = "0 0 2 * * *")
                    void export() {
                        String day = java.time.LocalDate.now().toString();
                        container.getBlobClient("invoices/" + day + ".csv").upload(BinaryData.fromString("x"));
                    }

                    @Scheduled(cron = "0 0 6 * * *")
                    void report(String name) {
                        new BlobContainerClientBuilder().containerName("reports").buildClient().getBlobClient(name).downloadContent();
                    }
                }
                """);
        source(svc, "Outbound.java", """
                package com.example.files;

                import com.jcraft.jsch.ChannelSftp;
                import net.schmizz.sshj.sftp.SFTPClient;
                import org.springframework.integration.file.remote.session.Session;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                class Outbound {
                    private static final String DIR = "/outbound/invoices/";

                    @Scheduled(cron = "0 30 2 * * *")
                    void push(ChannelSftp sftp, SFTPClient client, Session<?> session, String name) throws Exception {
                        sftp.put("local.csv", DIR + "invoices.csv");
                        sftp.get("/inbound/acks/ack.csv", "ack.csv");
                        client.get("/inbound/returns/" + name, "returns.csv");
                        session.write(new java.io.ByteArrayInputStream(new byte[0]), "/outbound/summary.csv");
                    }
                }
                """);
        List<Path> cp = new ArrayList<>(Fixtures.classpath());
        for (String p : System.getProperty("logast.test.filelibs").split(File.pathSeparator)) {
            cp.add(Path.of(p));
        }
        StringWriter out = new StringWriter();
        Export.java(svc, cp, dev.logast.policy.Policy.defaults(), "billing", "test", out);
        FactReader.Result r = FactReader.read(new StringReader(out.toString()));
        assertThat(r.problems()).isEmpty();
        List<String> files = r.edges().stream().filter(e -> e.to().startsWith("file-location:"))
                .map(e -> e.from() + " -" + e.kind() + "-> " + e.to() + " (" + e.attrs().get("via") + ")").toList();
        assertThat(files).contains(
                "job:billing:InvoiceExport.export -writes-> file-location:blob:billing-exports/invoices/{?}.csv (BlobClient.upload)",
                "job:billing:InvoiceExport.report -reads-> file-location:blob:reports/{?} (BlobClientBase.downloadContent)",
                "job:billing:Outbound.push -writes-> file-location:sftp:/outbound/invoices/invoices.csv (ChannelSftp.put)",
                "job:billing:Outbound.push -reads-> file-location:sftp:/inbound/acks/ack.csv (ChannelSftp.get)",
                "job:billing:Outbound.push -reads-> file-location:sftp:/inbound/returns/{?} (SFTPClient.get)",
                "job:billing:Outbound.push -writes-> file-location:sftp:/outbound/summary.csv (Session.write)");
        assertThat(r.edges()).filteredOn(e -> e.to().startsWith("file-location:")).extracting(Edge::evidence)
                .allSatisfy(ev -> assertThat(ev.line()).isPositive());
    }
}
