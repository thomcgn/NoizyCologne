package de.noizycologne.backend.discovery;

import org.springframework.stereotype.Component;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Central conservative city filter. No source URL, title, genre or page text can prove a city. */
@Component
public class CologneGeography {
    public record ConfirmedVenue(String id,String city,Set<@NonNull String> names,Set<@NonNull String> keys) { }
    public record Result(String status,String reason,@Nullable String venueId) {public boolean inside(){return status.equals("INSIDE");}}
    private final JsonNode districts;
    public CologneGeography() {
        try(var input=Objects.requireNonNull(getClass().getResourceAsStream("/geography/cologne-districts.geojson"),"Stadtgrenze fehlt")) {
            districts=Objects.requireNonNull(JsonMapper.builder().build().readTree(input));
        }catch(java.io.IOException e){throw new IllegalStateException("Stadtgrenze nicht lesbar",e);}
    }
    public static @NonNull String name(String value) {return Objects.requireNonNull(EventNormalizer.text(value).replace("ä","ae").replace("ö","oe").replace("ü","ue").replace("ß","ss").replace(" ",""));}
    public static String country(String value){String normalized=name(value);return Set.of("de","deu","deutschland","germany").contains(normalized)?"de":normalized;}
    public static boolean cologne(String city) {return Set.of("koeln","koln","cologne").contains(name(city));}
    public static Set<@NonNull String> keys(EventPlace place) {
        Set<@NonNull String> keys=new HashSet<>();
        if(!place.reference().isBlank()) try{keys.add("ref:"+CanonicalSourceUrl.reference(place.reference()));}catch(IllegalArgumentException ignored){/* Untrusted identifiers are not identities. */}
        if(!place.city().isBlank() && place.street().matches(".*\\d.*")) keys.add("address:"+name(place.street())+"|"+(cologne(place.city())?"koeln":name(place.city())));
        return Set.copyOf(keys);
    }
    public @NonNull Result evaluate(EventExtractor.Event event,List<@NonNull ConfirmedVenue> known) {
        var place=event.place();Set<String> evidence=new HashSet<>();
        var matches=known.stream().filter(venue -> venue.names().contains(name(event.location())) && venue.keys().stream().anyMatch(keys(place)::contains)).toList();
        if(matches.size()>1 || place.conflicting()) return new Result("UNRESOLVED","Widersprüchliche oder mehrdeutige Ortsbelege",null);
        var match=matches.isEmpty()?null:matches.getFirst();
        if(match!=null) evidence.add(cologne(match.city())?"INSIDE":"OUTSIDE");
        if(!place.city().isBlank()) {if(cologne(place.city()))evidence.add("INSIDE");else if(!name(place.city()).contains("koeln") && !name(place.city()).contains("cologne"))evidence.add("OUTSIDE");}
        if(!place.country().isBlank() && !country(place.country()).equals("de")) evidence.add("OUTSIDE");
        Double lat=place.latitude(),lon=place.longitude();
        if(lat!=null || lon!=null) {
            if(lat==null || lon==null || !Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat)>90 || Math.abs(lon)>180
                || java.math.BigDecimal.valueOf(lat).scale()<4 || java.math.BigDecimal.valueOf(lon).scale()<4)
                return new Result("UNRESOLVED","Koordinaten fehlen, sind ungültig oder zu ungenau",null);
            String point=point(lat,lon);
            if(point.equals("UNRESOLVED")) return new Result(point,"Koordinaten liegen auf einer Gebietsgrenze",null);
            evidence.add(point);
        }
        if(evidence.size()>1) return new Result("UNRESOLVED","Stadt, bestätigte Location und Koordinaten widersprechen sich",null);
        if(evidence.isEmpty()) return new Result("UNRESOLVED","Keine bestätigte Ortsidentität, Veranstaltungsstadt oder belastbaren Koordinaten",null);
        String status=Objects.requireNonNull(evidence.iterator().next());
        return new Result(status,status.equals("INSIDE")?"Veranstaltungsort innerhalb der Stadt Köln belegt":"Veranstaltungsort außerhalb der Stadt Köln belegt",match==null?null:match.id());
    }
    private String point(double lat,double lon) {
        for(var feature:districts.path("features")) {
            var geometry=feature.path("geometry");
            if(geometry.path("type").asString("").equals("Polygon")) {int result=polygon(geometry.path("coordinates"),lon,lat);if(result!=0)return result<0?"UNRESOLVED":"INSIDE";}
            else for(var polygon:geometry.path("coordinates")){int result=polygon(polygon,lon,lat);if(result!=0)return result<0?"UNRESOLVED":"INSIDE";}
        }
        return "OUTSIDE";
    }
    private int polygon(JsonNode rings,double x,double y) {
        boolean inside=false;
        for(var ring:rings) {
            boolean hit=false;
            for(int i=0,j=ring.size()-1;i<ring.size();j=i++) {
                double ax=ring.path(j).path(0).asDouble(),ay=ring.path(j).path(1).asDouble(),bx=ring.path(i).path(0).asDouble(),by=ring.path(i).path(1).asDouble();
                double cross=(x-ax)*(by-ay)-(y-ay)*(bx-ax);
                if(Math.abs(cross)<1e-13 && x>=Math.min(ax,bx) && x<=Math.max(ax,bx) && y>=Math.min(ay,by) && y<=Math.max(ay,by)) return -1;
                if((ay>y)!=(by>y) && x<(bx-ax)*(y-ay)/(by-ay)+ax) hit=!hit;
            }
            if(hit) inside=!inside;
        }
        return inside?1:0;
    }
}
