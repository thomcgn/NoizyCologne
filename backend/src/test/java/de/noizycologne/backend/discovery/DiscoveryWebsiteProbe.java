package de.noizycologne.backend.discovery;

import java.util.*;
import static org.mockito.Mockito.*;

/** Explicit read-only diagnostic, never part of the offline CI test suite. No Spring/DB context. */
public final class DiscoveryWebsiteProbe {
    private DiscoveryWebsiteProbe() { }
    public static void main(String[] arguments) throws java.io.IOException {
        if (arguments.length!=1) throw new IllegalArgumentException("Supply one public HTTPS website URL");
        var genres=mock(DiscoveryGenreFilter.class);
        when(genres.terms()).thenReturn(Set.of("Indie","Emo","Metalcore","Hardcore","Pop Punk","Alternative Rock","Rock","Metal"));
        String endpoint=Objects.toString(System.getenv("DISCOVERY_PROBE_BROWSER_URL"),"");
        var browser=new BrowserPageRenderer(!endpoint.isBlank(),endpoint.isBlank() ? "http://browser-gateway:3128/render" : endpoint,
            Objects.toString(System.getenv("BROWSER_WORKER_TOKEN"),"")) {
            @Override public Result render(java.net.URI website) throws java.io.IOException {
                var result=super.render(website);
                String output=System.getenv("DISCOVERY_PROBE_OUTPUT");
                if(output!=null && !output.isBlank()) {
                    var directory=java.nio.file.Path.of(output);java.nio.file.Files.createDirectories(directory);
                    java.nio.file.Files.writeString(directory.resolve(EventNormalizer.hash(website.toString())+".json"),tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(result));
                }
                return result;
            }
        };
        var discovery=new VenueSourceDiscoveryService(new EventPageFetcher(),new EventExtractor(),genres,null,null,browser);
        Map<String,EventExtractor.Event> events=new LinkedHashMap<>();
        try {
            for (var found:discovery.discover(arguments[0],EventExtractor.Profile.defaults())) {
                System.out.println("SOURCE "+found.url()+" | "+found.fetchMethod()+" | "+found.extraction().status()+" | complete="+found.extraction().complete()+" | "+found.diagnostics());
                for (var event:found.extraction().events()) {
                    var safe=EventPrivacy.safe(event);
                    events.putIfAbsent(EventNormalizer.text(safe.title())+"|"+safe.starts(),safe);
                }
            }
            for (var event:events.values()) System.out.println("EVENT "+event.title()+" | "+event.starts()+" | "+event.url());
            System.out.println("UNIQUE_EXTRACTED_EVENTS="+events.size());
        } catch (EventPageFetcher.FetchFailure e) { System.out.println("SOURCE_FAILED="+e.status); }
    }
}
