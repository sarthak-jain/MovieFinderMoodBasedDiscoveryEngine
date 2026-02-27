package com.moviefinder.model;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.util.Map;

@Node("Mood")
public class Mood {

    @Id
    @GeneratedValue
    private Long id;

    private String name;
    private String description;
    private String emoji;
    private Map<String, Double> tagWeights;

    public Mood() {}

    public Mood(String name, String description, String emoji) {
        this.name = name;
        this.description = description;
        this.emoji = emoji;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }

    public Map<String, Double> getTagWeights() { return tagWeights; }
    public void setTagWeights(Map<String, Double> tagWeights) { this.tagWeights = tagWeights; }
}
