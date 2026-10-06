package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.TextInterpolator;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.server.Messages;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Send an HTTP request (GET or POST) from an automation — the generic outbound hook for
 * Home Assistant webhooks, IFTTT, Node-RED, ntfy, etc.
 *
 * <p>Like {@link MqttPublishAction} this is deliberately NOT an {@link ApiAction}: it
 * targets arbitrary external hosts, not the local API allowlist. URL, body and header
 * values support the same {@code ${var:NAME}} / {@code ${signal:TYPE}} / {@code ${NAME}}
 * interpolation as other text fields. Headers are one {@code Name: value} per line.
 *
 * <p>Fire-and-forget: the request is enqueued on OkHttp's dispatcher so a slow endpoint
 * never stalls the automation engine. Failures are logged, never thrown.
 */
public class WebhookAction extends BaseAction {
    private static final String TYPE = "webhook";

    // One shared client (connection pool + dispatcher); timeouts keep a dead endpoint
    // from holding a worker thread for long.
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    private final Label label;
    private final String description;
    private final List<Type> variables = List.of(
            new StringType(new Label("url", "automation.webhook_url"), 512),
            new EnumType(new Label("method", "automation.webhook_method"),
                    new Label("GET", "automation.webhook_method_get"),
                    new Label("POST", "automation.webhook_method_post")),
            new StringType(new Label("body", "automation.webhook_body"), 1024),
            new StringType(new Label("headers", "automation.webhook_headers"), 1024));

    public WebhookAction(Label label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getType() { return TYPE; }
    public Label getLabel() { return label; }
    public String getDescription() { return Messages.get(description); }
    public List<Type> getVariables() { return variables; }

    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        String url = TextInterpolator.interpolate(str(vars.get("url")));
        boolean post = "POST".equalsIgnoreCase(str(vars.get("method")));
        String body = TextInterpolator.interpolate(raw(vars.get("body")));
        String headers = raw(vars.get("headers"));

        if (url == null || url.isEmpty()) {
            logger.warn("WebhookAction: empty URL, skipping");
            return;
        }
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            if (parsed == null) {
                logger.warn("WebhookAction: invalid URL, skipping");
                return;
            }
            Request.Builder rb = new Request.Builder().url(parsed);

            String contentType = null;
            if (headers != null) {
                for (String line : headers.split("\\r?\\n")) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) continue; // blank / malformed line
                    String name = line.substring(0, colon).trim();
                    String value = TextInterpolator.interpolate(line.substring(colon + 1).trim());
                    if (name.isEmpty()) continue;
                    try {
                        rb.addHeader(name, value == null ? "" : value);
                    } catch (IllegalArgumentException e) {
                        logger.warn("WebhookAction: skipping invalid header '" + name + "'");
                        continue;
                    }
                    if ("content-type".equalsIgnoreCase(name)) contentType = value;
                }
            }

            if (post) {
                String payload = body == null ? "" : body;
                if (contentType == null) {
                    String t = payload.trim();
                    contentType = (t.startsWith("{") || t.startsWith("["))
                            ? "application/json; charset=utf-8" : "text/plain; charset=utf-8";
                }
                MediaType mt = MediaType.parse(contentType);
                rb.post(RequestBody.create(payload, mt));
            } else {
                rb.get();
            }

            final String method = post ? "POST" : "GET";
            final String host = parsed.host();
            CLIENT.newCall(rb.build()).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    logger.warn("WebhookAction: " + method + " " + host + " failed: " + e.getMessage());
                }
                @Override public void onResponse(Call call, Response response) {
                    try (Response r = response) {
                        logger.info("WebhookAction: " + method + " " + host + " -> HTTP " + r.code());
                    }
                }
            });
        } catch (Throwable t) {
            logger.warn("WebhookAction failed: " + t.getMessage());
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString().trim(); }
    // Body/headers keep their inner newlines and spacing.
    private static String raw(Object o) { return o == null ? null : o.toString(); }
}
