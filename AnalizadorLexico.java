import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Analizador léxico basado en un AFD.
 *
 * Estados (iguales al diagrama):
 *   q0 inicio          q1 ID / palabra reservada   q2 NUM_ENTERO
 *   q3 vio "." (no final)  q4 NUM_REAL             q5 < > =
 *   q6 <= >= ==        q7 + - * /                  q8 ( ) { } ; ,
 *
 * Usa la regla de la coincidencia más larga: avanza mientras haya transición
 * y, al atascarse, retrocede al último estado de aceptación visitado.
 */
public class AnalizadorLexico {

    // ---------------------------------------------------------------- Tokens

    public enum TipoToken {
        ID, PALABRA_RESERVADA, NUM_ENTERO, NUM_REAL,
        OP_REL, ASIG, OP_ARIT, DELIMITADOR, ERROR, EOF
    }

    public static final class Token {
        public final TipoToken tipo;
        public final String lexema;
        public final int linea;
        public final int columna;

        public Token(TipoToken tipo, String lexema, int linea, int columna) {
            this.tipo = tipo;
            this.lexema = lexema;
            this.linea = linea;
            this.columna = columna;
        }

        @Override
        public String toString() {
            return String.format("%-18s %-12s (línea %d, col %d)",
                    tipo, "'" + lexema + "'", linea, columna);
        }
    }

    // ------------------------------------------------------------- El AFD

    private static final int ERROR_TRANSICION = -1;

    /** F: estados de aceptación, indexados por número de estado. */
    private static final boolean[] ACEPTACION = {
        false, // q0
        true,  // q1
        true,  // q2
        false, // q3
        true,  // q4
        true,  // q5
        true,  // q6
        true,  // q7
        true   // q8
    };

    private static final Set<String> PALABRAS_RESERVADAS = Set.of(
            "if", "else", "while", "for", "do", "int", "float",
            "char", "void", "return", "break", "continue"
    );

    /** Función de transición δ(estado, c). Devuelve -1 si no hay transición. */
    private static int delta(int estado, char c) {
        switch (estado) {
            case 0:
                if (esLetra(c) || c == '_')          return 1;
                if (esDigito(c))                     return 2;
                if (c == '<' || c == '>' || c == '=') return 5;
                if ("+-*/".indexOf(c) >= 0)          return 7;
                if ("(){};,".indexOf(c) >= 0)        return 8;
                return ERROR_TRANSICION;
            case 1:
                return (esLetra(c) || esDigito(c) || c == '_') ? 1 : ERROR_TRANSICION;
            case 2:
                if (esDigito(c)) return 2;
                if (c == '.')    return 3;
                return ERROR_TRANSICION;
            case 3:
            case 4:
                return esDigito(c) ? 4 : ERROR_TRANSICION;
            case 5:
                return (c == '=') ? 6 : ERROR_TRANSICION;
            default: // q6, q7, q8 no tienen transiciones de salida
                return ERROR_TRANSICION;
        }
    }

    /** Traduce el estado de aceptación final al tipo de token. */
    private static TipoToken tipoDeEstado(int estado, String lexema) {
        switch (estado) {
            case 1: return PALABRAS_RESERVADAS.contains(lexema)
                    ? TipoToken.PALABRA_RESERVADA : TipoToken.ID;
            case 2: return TipoToken.NUM_ENTERO;
            case 4: return TipoToken.NUM_REAL;
            case 5: return lexema.equals("=") ? TipoToken.ASIG : TipoToken.OP_REL;
            case 6: return TipoToken.OP_REL;
            case 7: return TipoToken.OP_ARIT;
            case 8: return TipoToken.DELIMITADOR;
            default: return TipoToken.ERROR;
        }
    }

    private static boolean esLetra(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean esDigito(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean esEspacio(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    // ------------------------------------------------------------ Escáner

    private final String fuente;
    private int pos = 0;
    private int linea = 1;
    private int columna = 1;

    public AnalizadorLexico(String fuente) {
        this.fuente = fuente;
    }

    private void avanzar() {
        if (fuente.charAt(pos) == '\n') {
            linea++;
            columna = 1;
        } else {
            columna++;
        }
        pos++;
    }

    /** Devuelve el siguiente token de la entrada. */
    public Token siguienteToken() {
        // Bucle de q0: ignorar espacios, tabuladores y saltos de línea
        while (pos < fuente.length() && esEspacio(fuente.charAt(pos))) {
            avanzar();
        }
        if (pos >= fuente.length()) {
            return new Token(TipoToken.EOF, "", linea, columna);
        }

        int lineaInicio = linea;
        int columnaInicio = columna;

        int estado = 0;
        int ultimoEstadoAceptado = -1;
        int finUltimoAceptado = pos;

        // Recorre el AFD sin consumir todavía (lookahead)
        for (int i = pos; i < fuente.length(); i++) {
            int siguiente = delta(estado, fuente.charAt(i));
            if (siguiente == ERROR_TRANSICION) break;
            estado = siguiente;
            if (ACEPTACION[estado]) {
                ultimoEstadoAceptado = estado;
                finUltimoAceptado = i + 1;
            }
        }

        // Ningún estado de aceptación: carácter inválido
        if (ultimoEstadoAceptado == -1) {
            String malo = String.valueOf(fuente.charAt(pos));
            avanzar();
            return new Token(TipoToken.ERROR, malo, lineaInicio, columnaInicio);
        }

        // Retroceso implícito: solo se consume hasta el último estado final
        String lexema = fuente.substring(pos, finUltimoAceptado);
        while (pos < finUltimoAceptado) {
            avanzar();
        }
        return new Token(tipoDeEstado(ultimoEstadoAceptado, lexema),
                lexema, lineaInicio, columnaInicio);
    }

    /** Analiza toda la entrada y devuelve la lista de tokens (incluye EOF). */
    public List<Token> tokenizar() {
        List<Token> tokens = new ArrayList<>();
        Token t;
        do {
            t = siguienteToken();
            tokens.add(t);
        } while (t.tipo != TipoToken.EOF);
        return tokens;
    }

    // --------------------------------------------------------------- Main

    public static void main(String[] args) throws IOException {
        String codigo;
        if (args.length > 0) {
            codigo = Files.readString(Path.of(args[0]));
        } else {
            codigo = "int contador = 10;\n"
                   + "float pi_2 = 3.1416;\n"
                   + "while (contador >= 0) {\n"
                   + "    contador = contador - 1;\n"
                   + "    if (x == 12.) y = y * 2 @;\n"
                   + "}\n";
        }

        System.out.println("Entrada:\n" + codigo);
        System.out.println("Tokens:");
        int errores = 0;
        for (Token t : new AnalizadorLexico(codigo).tokenizar()) {
            System.out.println("  " + t);
            if (t.tipo == TipoToken.ERROR) errores++;
        }
        System.out.println("\nErrores léxicos: " + errores);
    }
}
