# Offline city geometry

City of Cologne: union of its nine administrative district polygons, WGS84.
Downloaded 2026-11-06, without simplification or a surrounding radius.
Data source/attribution: Stadt Köln – offenedaten-koeln.de.
Published layer (districts; last data edit 2024-12-14):
https://services.arcgis.com/ObdAEOfl1Z5LP2D0/arcgis/rest/services/K%C3%B6ln/FeatureServer/6
Snapshot query: /query?where=1%3D1&outFields=NAME&outSR=4326&f=geojson
The municipal KGG dataset is published under Datenlizenz Deutschland – Zero 2.0:
https://data.gov.de/suche/daten/kommunale-gebietsgliederung-kgg-stadt-koln

The application makes no runtime geocoding/network calls. City metadata and
confirmed venue identities take priority; coordinates are checked against these
polygons, never an approximate bounding box. Invalid or low precision points and
points on polygon edges remain unresolved. Updating the snapshot is a reviewed
repository change and requires the geography regressions.
