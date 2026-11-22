# Hemligheter

Lägg filer här som inte ska in i git, t.ex. Google-tjänstekontots nyckel:

    secrets/google-sa.json

Mappen monteras skrivskyddad som `/app/secrets` i Pluggis-containern. Allt utom den här filen ignoreras av git.
