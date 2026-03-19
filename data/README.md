# Dataset: Kaggle Amazon Electronics Products

## Wohin die CSV-Datei legen?

Lege die heruntergeladene CSV-Datei unter folgendem Pfad ab:

```
/home/bacher/prj/new-commerce/data/electronics.csv
```

Dieser Pfad entspricht dem Standardwert der Konfigurationseigenschaft `ingestion.csv.path` in `application.properties`.

## Download

1. Kaggle-Datensatz: **Amazon Electronics Products 10k items**
   URL: https://www.kaggle.com/datasets/lokeshparab/amazon-products-dataset (oder vergleichbar)

2. Datei umbenennen/kopieren nach `data/electronics.csv`

## CSV-Format

Die Datei muss folgende 9 Spalten (Header-Zeile) haben:

| Spalte          | Beschreibung                        | Beispiel                    |
|-----------------|-------------------------------------|-----------------------------|
| name            | Produktname                         | Samsung 80 cm (32 inches).. |
| main_category   | Hauptkategorie                      | stores                      |
| sub_category    | Unterkategorie                      | televisions                 |
| image           | Bild-URL                            | https://...                 |
| link            | Amazon-Produktlink                  | https://amazon.in/...       |
| ratings         | Durchschnittsbewertung (0–5)        | 4.2                         |
| no_of_ratings   | Anzahl Bewertungen                  | 22,497                      |
| discount_price  | Aktueller Preis (mit Rupie-Symbol)  | ₹15,990                     |
| actual_price    | Originalpreis (mit Rupie-Symbol)    | ₹28,900                     |

## Pfad uberschreiben

Der Pfad kann beim Start der Anwendung uberschrieben werden:

```bash
java -jar target/commerce-backend-0.0.1-SNAPSHOT.jar \
  --ingestion.csv.path=/pfad/zur/datei.csv
```

Oder via REST API beim Trigger-Aufruf:

```bash
curl -X POST "http://localhost:8080/api/v1/ingestion/trigger?csvPath=/pfad/zur/datei.csv"
```

## TV-Filter

Wenn `ingestion.tv.filter=true` (Standardwert), werden nur Zeilen importiert,
bei denen `sub_category` das Wort "television" enthalt (case-insensitive)
oder `name` den String "TV" enthalt.

Um alle Produkte zu importieren: `ingestion.tv.filter=false`
