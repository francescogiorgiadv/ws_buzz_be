package it.wsbuzz.backend.room;

/**
 * Modalita' di gioco di una stanza: definisce cosa succede quando un
 * giocatore preme il buzzer.
 */
public enum RoomMode {
  /** Il primo che preme vince: il buzzer si blocca per tutti gli altri. */
  FASTEST,
  /** Tutti possono premere: si forma una classifica in base ai tempi, il primo e' il vincitore. */
  LEADERBOARD,
  /** Il buzzer e' sostituito da un'area dove ogni giocatore scrive una definizione. */
  DEFINITIONS,
  /** Il buzzer e' sostituito da quattro risposte (A/B/C/D) tra cui scegliere. */
  MULTIPLE_CHOICE,
}
