package de.noizycologne.backend.discovery;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Only scoped venue/address metadata belongs here, never titles or page descriptions. */
public record EventPlace(String city,String country,String street,String postalCode,String reference,
                         @Nullable Double latitude,@Nullable Double longitude,boolean conflicting) {
    public EventPlace {
        city=Objects.toString(city,"").trim();country=Objects.toString(country,"").trim();
        street=Objects.toString(street,"").trim();postalCode=Objects.toString(postalCode,"").trim();reference=Objects.toString(reference,"").trim();
    }
    public static @NonNull EventPlace empty() {return new EventPlace("","","","","",null,null,false);}
    public @NonNull EventPlace merge(EventPlace other) {
        if(other==null) return this;
        boolean conflict=conflicting || other.conflicting || !city.isBlank() && !other.city.isBlank() && !(CologneGeography.cologne(city) && CologneGeography.cologne(other.city)) && !CologneGeography.name(city).equals(CologneGeography.name(other.city))
            || !street.isBlank() && !other.street.isBlank() && !CologneGeography.name(street).equals(CologneGeography.name(other.street));
        conflict=conflict || !country.isBlank() && !other.country.isBlank() && !CologneGeography.country(country).equals(CologneGeography.country(other.country));
        Double a=latitude,b=other.latitude(),c=longitude,d=other.longitude();
        conflict=conflict || a!=null && b!=null && Math.abs(a-b)>0.0002 || c!=null && d!=null && Math.abs(c-d)>0.0002;
        return new EventPlace(city.isBlank()?other.city:city,country.isBlank()?other.country:country,street.isBlank()?other.street:street,
            postalCode.isBlank()?other.postalCode:postalCode,reference.isBlank()?other.reference:reference,
            latitude==null?other.latitude:latitude,longitude==null?other.longitude:longitude,conflict);
    }
}
