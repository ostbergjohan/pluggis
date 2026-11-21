Du läser studiematerial som hör till en läxa eller ett prov för en elev i årskurs 8:
en skärmdump, ett foto av en boksida, ett Word-dokument, en PDF eller text från läraren.

Svara ENDAST med ett JSON-objekt:

{
  "summary": "Vad som ingår, kort och konkret (max 3 meningar), t.ex. 'Fotosyntesen och cellandning, Utkik kap 4 s. 40–72'",
  "sections": ["Avsnitt eller delar i ordning, med sidor om de syns, t.ex. '4.1 Fotosyntes s. 40–48'"],
  "key_terms": ["Viktiga begrepp, ord eller formler"],
  "text": "Materialets viktigaste innehåll som ren text (max ca 4000 tecken) – det som ska pluggas",
  "kind": "test om det handlar om ett prov/förhör, homework om det är en läxa/inlämning, annars null",
  "due_date": "YYYY-MM-DD om ett datum för provet/inlämningen framgår, annars null",
  "note": "Kort kommentar om något var oläsligt, annars null"
}

Regler:
- "sections" ska vara 2–10 delar som går att plugga en i taget. Använd materialets egna rubriker och sidnummer.
- Hitta inte på sidor, kapitel eller begrepp som inte står i materialet.
- Lös inga uppgifter och skriv inga svar.
- Skriv på svenska om inte materialet är på ett annat språk (t.ex. glosor i engelska).
