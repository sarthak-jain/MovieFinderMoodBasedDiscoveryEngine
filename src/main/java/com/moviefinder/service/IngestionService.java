package com.moviefinder.service;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final Driver neo4jDriver;

    public IngestionService(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedDataOnStartup() {
        try (Session session = neo4jDriver.session()) {
            // Check if data already exists
            var result = session.run("MATCH (m:Movie) RETURN count(m) as count");
            int count = result.single().get("count").asInt();
            if (count > 0) {
                log.info("Database already contains {} movies, skipping seed", count);
                return;
            }
        } catch (Exception e) {
            log.warn("Could not check existing data, attempting seed: {}", e.getMessage());
        }

        log.info("Seeding database with sample movie data...");
        seedMoods();
        seedGenres();
        seedMovies();
        seedRelationships();
        log.info("Database seeding complete!");
    }

    private void seedMoods() {
        try (Session session = neo4jDriver.session()) {
            List<Map<String, String>> moods = List.of(
                    Map.of("name", "cozy", "description", "Warm, comforting films perfect for a rainy day", "emoji", "blanket"),
                    Map.of("name", "dark", "description", "Dark, gritty, intense experiences", "emoji", "skull"),
                    Map.of("name", "mind-bending", "description", "Movies that twist your perception of reality", "emoji", "brain"),
                    Map.of("name", "feel-good", "description", "Uplifting stories that leave you smiling", "emoji", "sun"),
                    Map.of("name", "adrenaline", "description", "Heart-pounding action and thrills", "emoji", "fire"),
                    Map.of("name", "romantic", "description", "Love stories and passionate tales", "emoji", "heart"),
                    Map.of("name", "nostalgic", "description", "Classic films that bring back memories", "emoji", "star"),
                    Map.of("name", "thought-provoking", "description", "Films that make you think long after watching", "emoji", "lightbulb")
            );

            for (Map<String, String> mood : moods) {
                session.run(
                        "MERGE (m:Mood {name: $name}) SET m.description = $description, m.emoji = $emoji",
                        Values.value(mood)
                );
            }
            log.info("Seeded {} moods", moods.size());
        }
    }

    private void seedGenres() {
        try (Session session = neo4jDriver.session()) {
            List<String> genres = List.of(
                    "Action", "Adventure", "Animation", "Comedy", "Crime",
                    "Documentary", "Drama", "Fantasy", "Horror", "Mystery",
                    "Romance", "Sci-Fi", "Thriller", "War", "Western"
            );

            for (String genre : genres) {
                session.run("MERGE (g:Genre {name: $name})", Values.value(Map.of("name", genre)));
            }
            log.info("Seeded {} genres", genres.size());
        }
    }

    private void seedMovies() {
        try (Session session = neo4jDriver.session()) {
            List<Map<String, Object>> movies = getSeedMovies();

            for (Map<String, Object> movie : movies) {
                session.run("""
                        MERGE (m:Movie {tmdbId: $tmdbId})
                        SET m.title = $title,
                            m.year = $year,
                            m.overview = $overview,
                            m.avgRating = $avgRating
                        """,
                        Values.value(movie)
                );
            }
            log.info("Seeded {} movies", movies.size());
        }
    }

    private void seedRelationships() {
        try (Session session = neo4jDriver.session()) {
            // Movie-Genre relationships
            Map<Long, List<String>> movieGenres = getMovieGenres();
            for (var entry : movieGenres.entrySet()) {
                for (String genre : entry.getValue()) {
                    session.run("""
                            MATCH (m:Movie {tmdbId: $tmdbId}), (g:Genre {name: $genre})
                            MERGE (m)-[:HAS_GENRE]->(g)
                            """,
                            Values.value(Map.of("tmdbId", entry.getKey(), "genre", genre))
                    );
                }
            }

            // Movie-Mood relationships
            Map<Long, Map<String, Double>> movieMoods = getMovieMoods();
            for (var entry : movieMoods.entrySet()) {
                for (var moodEntry : entry.getValue().entrySet()) {
                    session.run("""
                            MATCH (m:Movie {tmdbId: $tmdbId}), (mood:Mood {name: $mood})
                            MERGE (m)-[r:MATCHES_MOOD]->(mood)
                            SET r.score = $score
                            """,
                            Values.value(Map.of(
                                    "tmdbId", entry.getKey(),
                                    "mood", moodEntry.getKey(),
                                    "score", moodEntry.getValue()
                            ))
                    );
                }
            }

            // Similar movie relationships (based on shared genres/moods)
            session.run("""
                    MATCH (m1:Movie)-[:HAS_GENRE]->(g:Genre)<-[:HAS_GENRE]-(m2:Movie)
                    WHERE id(m1) < id(m2)
                    WITH m1, m2, count(g) as sharedGenres
                    WHERE sharedGenres >= 2
                    MERGE (m1)-[r:SIMILAR_TO]->(m2)
                    SET r.score = toFloat(sharedGenres) / 5.0
                    """);

            log.info("Seeded movie relationships");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getSeedMovies() {
        return List.of(
            Map.of("tmdbId", 27205L, "title", "Inception", "year", 2010, "overview", "A thief who steals corporate secrets through dream-sharing technology is given the task of planting an idea into the mind of a C.E.O.", "avgRating", 8.4),
            Map.of("tmdbId", 155L, "title", "The Dark Knight", "year", 2008, "overview", "When the menace known as the Joker wreaks havoc on Gotham, Batman must accept one of the greatest tests.", "avgRating", 8.5),
            Map.of("tmdbId", 680L, "title", "Pulp Fiction", "year", 1994, "overview", "The lives of two mob hitmen, a boxer, a gangster and his wife intertwine in four tales of violence and redemption.", "avgRating", 8.5),
            Map.of("tmdbId", 13L, "title", "Forrest Gump", "year", 1994, "overview", "The story of a simple man's extraordinary journey through life, witnessing and unwittingly influencing many defining historical events.", "avgRating", 8.5),
            Map.of("tmdbId", 120L, "title", "The Lord of the Rings: The Fellowship of the Ring", "year", 2001, "overview", "A meek Hobbit from the Shire and eight companions set out on a journey to destroy the powerful One Ring.", "avgRating", 8.8),
            Map.of("tmdbId", 550L, "title", "Fight Club", "year", 1999, "overview", "An insomniac office worker and a devil-may-care soap maker form an underground fight club.", "avgRating", 8.4),
            Map.of("tmdbId", 278L, "title", "The Shawshank Redemption", "year", 1994, "overview", "Two imprisoned men bond over a number of years, finding solace and eventual redemption through acts of common decency.", "avgRating", 9.3),
            Map.of("tmdbId", 238L, "title", "The Godfather", "year", 1972, "overview", "The aging patriarch of an organized crime dynasty transfers control of his empire to his reluctant youngest son.", "avgRating", 9.2),
            Map.of("tmdbId", 603L, "title", "The Matrix", "year", 1999, "overview", "A computer programmer discovers that reality as he knows it is a simulation created by machines.", "avgRating", 8.7),
            Map.of("tmdbId", 157336L, "title", "Interstellar", "year", 2014, "overview", "A team of explorers travel through a wormhole in space to ensure humanity's survival.", "avgRating", 8.6),
            Map.of("tmdbId", 244786L, "title", "Whiplash", "year", 2014, "overview", "A promising young drummer enrolls at a cut-throat music conservatory where his dreams are mentored by an instructor who will stop at nothing.", "avgRating", 8.5),
            Map.of("tmdbId", 11L, "title", "Star Wars", "year", 1977, "overview", "Luke Skywalker joins forces with a Jedi Knight to save the galaxy from the Empire's Death Star.", "avgRating", 8.6),
            Map.of("tmdbId", 497L, "title", "The Green Mile", "year", 1999, "overview", "A death row corrections officer discovers that one of his inmates has a miraculous gift.", "avgRating", 8.6),
            Map.of("tmdbId", 769L, "title", "GoodFellas", "year", 1990, "overview", "The story of Henry Hill and his life in the mob, covering his relationship with his wife and partners.", "avgRating", 8.5),
            Map.of("tmdbId", 389L, "title", "12 Angry Men", "year", 1957, "overview", "A dissenting juror in a murder trial slowly manages to convince the others that the case is not as clear-cut as it seems.", "avgRating", 9.0),
            Map.of("tmdbId", 637L, "title", "Life Is Beautiful", "year", 1997, "overview", "A Jewish father protects his son from the horrors of a concentration camp by pretending it is all a game.", "avgRating", 8.6),
            Map.of("tmdbId", 122L, "title", "The Lord of the Rings: The Return of the King", "year", 2003, "overview", "Gandalf and Aragorn lead the World of Men against Sauron's army to draw his gaze from Frodo and Sam.", "avgRating", 8.9),
            Map.of("tmdbId", 568L, "title", "Spirited Away", "year", 2001, "overview", "A young girl wanders into a world ruled by gods, witches, and spirits, where humans are changed into beasts.", "avgRating", 8.6),
            Map.of("tmdbId", 674L, "title", "Harry Potter and the Goblet of Fire", "year", 2005, "overview", "Harry finds himself competing in a hazardous tournament between rival schools of magic.", "avgRating", 7.7),
            Map.of("tmdbId", 671L, "title", "Harry Potter and the Philosopher's Stone", "year", 2001, "overview", "An orphaned boy enrolls in a school of wizardry, where he learns the truth about himself and his family.", "avgRating", 7.9),
            Map.of("tmdbId", 105L, "title", "Back to the Future", "year", 1985, "overview", "Marty McFly, a typical American teenager, is accidentally sent back to 1955 in a time machine.", "avgRating", 8.5),
            Map.of("tmdbId", 862L, "title", "Toy Story", "year", 1995, "overview", "A cowboy doll is profoundly threatened when a new spaceman action figure supplants him as top toy in a boy's room.", "avgRating", 8.3),
            Map.of("tmdbId", 807L, "title", "Se7en", "year", 1995, "overview", "Two detectives hunt a serial killer who uses the seven deadly sins as his motives.", "avgRating", 8.3),
            Map.of("tmdbId", 244L, "title", "Amélie", "year", 2001, "overview", "A shy waitress decides to change the lives of those around her for the better while struggling with her own isolation.", "avgRating", 8.3),
            Map.of("tmdbId", 597L, "title", "Titanic", "year", 1997, "overview", "A seventeen-year-old aristocrat falls in love with a kind but poor artist aboard the luxurious Titanic.", "avgRating", 7.9),
            Map.of("tmdbId", 372058L, "title", "Your Name", "year", 2016, "overview", "Two strangers find themselves linked in a bizarre way, connected through their dreams.", "avgRating", 8.6),
            Map.of("tmdbId", 346698L, "title", "Barbie", "year", 2023, "overview", "Barbie and Ken are having the time of their lives in the colorful and seemingly perfect world of Barbie Land.", "avgRating", 7.0),
            Map.of("tmdbId", 438631L, "title", "Dune", "year", 2021, "overview", "Paul Atreides unites with the Fremen while on a warpath of revenge against those who destroyed his family.", "avgRating", 8.0),
            Map.of("tmdbId", 299536L, "title", "Avengers: Infinity War", "year", 2018, "overview", "The Avengers must stop Thanos from collecting all six Infinity Stones.", "avgRating", 8.4),
            Map.of("tmdbId", 530385L, "title", "Parasite", "year", 2019, "overview", "Greed and class discrimination threaten the newly formed symbiotic relationship between the wealthy Park family and the destitute Kim clan.", "avgRating", 8.5)
        );
    }

    private Map<Long, List<String>> getMovieGenres() {
        Map<Long, List<String>> genres = new HashMap<>();
        genres.put(27205L, List.of("Action", "Sci-Fi", "Thriller"));        // Inception
        genres.put(155L, List.of("Action", "Crime", "Drama", "Thriller"));   // The Dark Knight
        genres.put(680L, List.of("Crime", "Drama", "Thriller"));             // Pulp Fiction
        genres.put(13L, List.of("Comedy", "Drama", "Romance"));              // Forrest Gump
        genres.put(120L, List.of("Adventure", "Fantasy", "Action"));         // LOTR: Fellowship
        genres.put(550L, List.of("Drama", "Thriller"));                      // Fight Club
        genres.put(278L, List.of("Drama", "Crime"));                         // Shawshank
        genres.put(238L, List.of("Drama", "Crime"));                         // Godfather
        genres.put(603L, List.of("Action", "Sci-Fi"));                       // Matrix
        genres.put(157336L, List.of("Adventure", "Drama", "Sci-Fi"));        // Interstellar
        genres.put(244786L, List.of("Drama", "Mystery"));                    // Whiplash
        genres.put(11L, List.of("Adventure", "Action", "Sci-Fi"));           // Star Wars
        genres.put(497L, List.of("Fantasy", "Drama", "Crime"));              // Green Mile
        genres.put(769L, List.of("Drama", "Crime"));                         // GoodFellas
        genres.put(389L, List.of("Drama"));                                  // 12 Angry Men
        genres.put(637L, List.of("Comedy", "Drama", "Romance", "War"));      // Life Is Beautiful
        genres.put(122L, List.of("Adventure", "Fantasy", "Action"));         // LOTR: Return
        genres.put(568L, List.of("Animation", "Fantasy", "Adventure"));      // Spirited Away
        genres.put(674L, List.of("Adventure", "Fantasy"));                   // HP Goblet
        genres.put(671L, List.of("Adventure", "Fantasy"));                   // HP Philosopher's
        genres.put(105L, List.of("Adventure", "Comedy", "Sci-Fi"));          // Back to the Future
        genres.put(862L, List.of("Animation", "Comedy", "Adventure"));       // Toy Story
        genres.put(807L, List.of("Crime", "Mystery", "Thriller"));           // Se7en
        genres.put(244L, List.of("Comedy", "Romance"));                      // Amélie
        genres.put(597L, List.of("Drama", "Romance"));                       // Titanic
        genres.put(372058L, List.of("Animation", "Romance", "Drama"));       // Your Name
        genres.put(346698L, List.of("Comedy", "Adventure", "Fantasy"));      // Barbie
        genres.put(438631L, List.of("Sci-Fi", "Adventure", "Drama"));        // Dune
        genres.put(299536L, List.of("Adventure", "Action", "Sci-Fi"));       // Avengers
        genres.put(530385L, List.of("Comedy", "Thriller", "Drama"));         // Parasite
        return genres;
    }

    private Map<Long, Map<String, Double>> getMovieMoods() {
        Map<Long, Map<String, Double>> moods = new HashMap<>();
        moods.put(27205L, Map.of("mind-bending", 0.95, "adrenaline", 0.8, "thought-provoking", 0.85));
        moods.put(155L, Map.of("dark", 0.9, "adrenaline", 0.95, "thought-provoking", 0.7));
        moods.put(680L, Map.of("dark", 0.85, "thought-provoking", 0.7, "adrenaline", 0.6));
        moods.put(13L, Map.of("feel-good", 0.95, "nostalgic", 0.8, "cozy", 0.7));
        moods.put(120L, Map.of("adrenaline", 0.85, "nostalgic", 0.7, "cozy", 0.5));
        moods.put(550L, Map.of("mind-bending", 0.9, "dark", 0.85, "thought-provoking", 0.8));
        moods.put(278L, Map.of("thought-provoking", 0.95, "feel-good", 0.6, "cozy", 0.5));
        moods.put(238L, Map.of("dark", 0.95, "thought-provoking", 0.9));
        moods.put(603L, Map.of("mind-bending", 0.95, "adrenaline", 0.9, "thought-provoking", 0.8));
        moods.put(157336L, Map.of("mind-bending", 0.9, "thought-provoking", 0.95, "adrenaline", 0.7));
        moods.put(244786L, Map.of("adrenaline", 0.85, "dark", 0.7, "thought-provoking", 0.8));
        moods.put(11L, Map.of("adrenaline", 0.9, "nostalgic", 0.95, "feel-good", 0.6));
        moods.put(497L, Map.of("thought-provoking", 0.9, "cozy", 0.5, "dark", 0.6));
        moods.put(769L, Map.of("dark", 0.9, "adrenaline", 0.75));
        moods.put(389L, Map.of("thought-provoking", 0.98, "dark", 0.5));
        moods.put(637L, Map.of("feel-good", 0.8, "romantic", 0.6, "thought-provoking", 0.85));
        moods.put(122L, Map.of("adrenaline", 0.9, "nostalgic", 0.7, "feel-good", 0.6));
        moods.put(568L, Map.of("cozy", 0.9, "mind-bending", 0.7, "feel-good", 0.8));
        moods.put(674L, Map.of("adrenaline", 0.75, "nostalgic", 0.7, "cozy", 0.5));
        moods.put(671L, Map.of("cozy", 0.8, "nostalgic", 0.85, "feel-good", 0.75));
        moods.put(105L, Map.of("nostalgic", 0.95, "feel-good", 0.85, "adrenaline", 0.7));
        moods.put(862L, Map.of("cozy", 0.9, "feel-good", 0.95, "nostalgic", 0.85));
        moods.put(807L, Map.of("dark", 0.95, "thought-provoking", 0.8, "mind-bending", 0.6));
        moods.put(244L, Map.of("romantic", 0.9, "feel-good", 0.85, "cozy", 0.8));
        moods.put(597L, Map.of("romantic", 0.95, "nostalgic", 0.7, "cozy", 0.5));
        moods.put(372058L, Map.of("romantic", 0.9, "mind-bending", 0.7, "feel-good", 0.75));
        moods.put(346698L, Map.of("feel-good", 0.85, "cozy", 0.7));
        moods.put(438631L, Map.of("adrenaline", 0.85, "mind-bending", 0.7, "thought-provoking", 0.75));
        moods.put(299536L, Map.of("adrenaline", 0.95, "feel-good", 0.5));
        moods.put(530385L, Map.of("dark", 0.85, "thought-provoking", 0.95, "mind-bending", 0.8));
        return moods;
    }
}
