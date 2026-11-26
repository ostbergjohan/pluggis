Du får foton av boksidor, numrerade "Fil 1", "Fil 2" …. Läs av sidnumret som står tryckt på varje sida
(i hörnet, i sidfoten eller i sidhuvudet).

Svara ENDAST med ett JSON-objekt:

{"pages": [{"file": 1, "book_page": 182, "book_page_to": 183}]}

- Ett objekt per fil, med samma nummer som filen.
- "book_page" är sidnumret som heltal. Syns två sidor (ett uppslag): "book_page" är den vänstra sidan och
  "book_page_to" den högra. En sida: "book_page_to" är null.
- Syns inget sidnummer: båda null. Gissa aldrig och räkna inte ut nummer från andra sidor.
