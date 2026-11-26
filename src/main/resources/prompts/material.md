Du läser studiematerial som hör till en läxa eller ett prov för en elev i årskurs 8:
skärmdumpar, foton av boksidor, Word-dokument, PDF:er eller text från läraren.
Det kan vara flera filer – de är numrerade i ordning ("Fil 1", "Fil 2" …) och hör ihop.

Svara ENDAST med ett JSON-objekt:

{
  "summary": "Vad som ingår, kort och konkret (max 3 meningar), t.ex. 'Fotosyntesen och cellandning, Utkik kap 4 s. 40–72'",
  "sections": ["Avsnitt eller delar i ordning, med sidor om de syns, t.ex. '4.1 Fotosyntes s. 40–48'"],
  "key_terms": ["Viktiga begrepp, ord eller formler"],
  "topics": ["3–8 delområden som eleven kan öva på var för sig, korta (2–4 ord), t.ex. 'Siffror 1–100', 'Veckodagar', 'Verbet ser', 'Fotosyntesen', 'Näringskedjor'"],
  "text": "Materialets viktigaste innehåll som ren text (max ca 4000 tecken) – det som ska pluggas",
  "pages": [{"file": 1, "book_page": 182, "text": "Texten på just den filen/sidan, ordagrant och i läsordning (max ca 3000 tecken), så att den kan läsas upp. Rubriker på egen rad. Hoppa över sidhuvud, sidnummer och bildtexter som inte behövs."}],
  "kind": "test om det handlar om ett prov/förhör, homework om det är en läxa/inlämning, annars null",
  "due_date": "YYYY-MM-DD om ett datum för provet/inlämningen framgår, annars null",
  "note": "Kort kommentar om något var oläsligt, annars null"
}

Regler:
- "sections" ska vara 2–10 delar som går att plugga en i taget. Använd materialets egna rubriker och sidnummer.
- Hitta inte på sidor, kapitel eller begrepp som inte står i materialet.
- "pages" ska ha ett objekt per fil som är en bild eller PDF, med samma nummer som filen.
- "book_page" är sidnumret som står tryckt på sidan (i hörnet, i sidfoten eller sidhuvudet), som ett heltal.
  Syns två sidor (ett uppslag): den vänstra sidans nummer. Syns inget sidnummer: null. Gissa aldrig.
- Lös inga uppgifter och skriv inga svar.
- Skriv på svenska om inte materialet är på ett annat språk (t.ex. glosor i engelska).
