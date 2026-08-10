package net.fjordomatic.domain.port;

public interface ForPublishingEvents {
    void publish(String topic, String eventName, String data);
}
