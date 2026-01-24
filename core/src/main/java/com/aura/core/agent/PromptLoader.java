package com.aura.core.agent;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptLoader {

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public String load(String name) {
        return cache.computeIfAbsent(name, this::read);
    }

    private String read(String name) {
        try (var is = new ClassPathResource("prompts/" + name + ".txt").getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Missing prompt: " + name, e);
        }
    }
}
