package com.wireweave.adapter.driven;

import com.wireweave.domain.DnsRecord;
import com.wireweave.domain.Domain;
import com.wireweave.domain.port.ForGettingDnsInfo;
import java.util.stream.Collectors;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;

import java.util.ArrayList;
import java.util.List;

public class Route53Adapter implements ForGettingDnsInfo {

    private final Route53Client route53Client;

    public Route53Adapter() {
        this.route53Client = Route53Client.builder().build();
    }

    public Route53Adapter(Route53Client route53Client) {
        this.route53Client = route53Client;
    }

    @Override
    public List<DnsRecord> getDnsRecords(Domain domain) {
        try {
            String hostedZoneId = findHostedZoneId(domain.name());
            if (hostedZoneId == null) {
                return List.of();
            }

            List<DnsRecord> dnsRecords = new ArrayList<>();
            String nextRecordName = null;
            String nextRecordType = null;

            do {
                ListResourceRecordSetsRequest.Builder requestBuilder = ListResourceRecordSetsRequest.builder()
                        .hostedZoneId(hostedZoneId);

                if (nextRecordName != null) {
                    requestBuilder.startRecordName(nextRecordName);
                    requestBuilder.startRecordType(nextRecordType);
                }

                ListResourceRecordSetsResponse response = route53Client.listResourceRecordSets(requestBuilder.build());

                for (ResourceRecordSet recordSet : response.resourceRecordSets()) {
                    DnsRecord dnsRecord = mapToDnsRecord(recordSet);
                    dnsRecords.add(dnsRecord);
                }

                if (response.isTruncated()) {
                    nextRecordName = response.nextRecordName();
                    nextRecordType = response.nextRecordTypeAsString();
                } else {
                    nextRecordName = null;
                }

            } while (nextRecordName != null);

            return dnsRecords;

        } catch (Route53Exception e) {
            throw new RuntimeException("Failed to get DNS records from Route53", e);
        }
    }

    @Override
    public List<Domain> getDomains() {
        return route53Client.listHostedZones().hostedZones().stream()
                .map(zone -> new Domain(zone.name()))
                .collect(Collectors.toList());
    }

    private String findHostedZoneId(String domainName) {
        try {
            ListHostedZonesByNameResponse response = route53Client.listHostedZonesByName(
                    ListHostedZonesByNameRequest.builder()
                            .dnsName(domainName)
                            .maxItems("1")
                            .build()
            );

            if (!response.hostedZones().isEmpty()) {
                HostedZone zone = response.hostedZones().getFirst();
                if (zone.name().equals(domainName) || zone.name().equals(domainName + ".")) {
                    // Strip /hostedzone/ prefix if present
                    String zoneId = zone.id();
                    return zoneId.startsWith("/hostedzone/") ? zoneId.substring(12) : zoneId;
                }
            }
            return null;
        } catch (Route53Exception e) {
            throw new RuntimeException("Failed to find hosted zone", e);
        }
    }

    private DnsRecord mapToDnsRecord(ResourceRecordSet recordSet) {
        String name = recordSet.name();
        String type = recordSet.typeAsString();
        Long ttl = recordSet.ttl();
        List<String> values = recordSet.resourceRecords().stream()
                .map(ResourceRecord::value)
                .toList();

        return new DnsRecord(name, type, ttl, values);
    }

    public static void main(String[] args) {
        String accessKeyId = System.getenv("WIREWEAVE_AWS_KEY");
        String secretAccessKey = System.getenv("WIREWEAVE_AWS_SECRET");
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
        Route53Client client = Route53Client.builder()
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .region(Region.AWS_GLOBAL)
            .build();

        Route53Adapter adapter = new Route53Adapter(client);

        List<Domain> domains = adapter.getDomains();
        domains.forEach(System.out::println);

        Domain domain = domains.getFirst();
        List<DnsRecord> records = adapter.getDnsRecords(domain);
        records.forEach(System.out::println);
    }
}
