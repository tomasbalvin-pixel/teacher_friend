package cz.teacherfriend.redpen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Výsledek hodnocení práce včetně značek, které lze v aplikaci dále upravovat. */
public final class GradingResult {
    public String grade = "";
    public String points = "";
    public String summary = "";
    public final List<String> strengths = new ArrayList<>();
    public final List<String> improvements = new ArrayList<>();
    public final List<Mark> marks = new ArrayList<>();

    public List<Mark> marksOnPage(int page) {
        List<Mark> out = new ArrayList<>();
        for (Mark m : marks) if (m.page == page) out.add(m);
        return out;
    }

    public Mark gradeMark() {
        for (Mark m : marks) if (m.kind == Mark.Kind.GRADE) return m;
        return null;
    }

    /** Přečísluje značky v pořadí čtení: stránka, shora dolů, zleva doprava. */
    public void renumber() {
        List<Mark> listed = new ArrayList<>();
        for (Mark m : marks) {
            m.number = 0;
            if (m.isListed()) listed.add(m);
        }
        Collections.sort(listed, new Comparator<Mark>() {
            @Override
            public int compare(Mark a, Mark b) {
                if (a.page != b.page) return Integer.compare(a.page, b.page);
                // Řádky: značky, jejichž středy jsou blízko sebe svisle, bereme jako jeden řádek.
                float ay = (a.y0 + a.y1) / 2, by = (b.y0 + b.y1) / 2;
                if (Math.abs(ay - by) > 0.015f) return Float.compare(ay, by);
                return Float.compare(a.x0, b.x0);
            }
        });
        for (int i = 0; i < listed.size(); i++) listed.get(i).number = i + 1;
    }

    public List<Mark> listedMarks() {
        List<Mark> out = new ArrayList<>();
        for (Mark m : marks) if (m.number > 0) out.add(m);
        Collections.sort(out, new Comparator<Mark>() {
            @Override
            public int compare(Mark a, Mark b) {
                return Integer.compare(a.number, b.number);
            }
        });
        return out;
    }

    /** Textové hodnocení pro zobrazení, sdílení i souhrnnou stránku PDF. */
    public String detailsText() {
        StringBuilder sb = new StringBuilder();
        if (!strengths.isEmpty()) {
            sb.append("Co se povedlo:\n");
            for (String s : strengths) sb.append("  + ").append(s).append('\n');
            sb.append('\n');
        }
        if (!improvements.isEmpty()) {
            sb.append("Na čem zapracovat:\n");
            for (String s : improvements) sb.append("  – ").append(s).append('\n');
            sb.append('\n');
        }
        List<Mark> listed = listedMarks();
        if (!listed.isEmpty()) {
            sb.append("Opravy:\n");
            for (Mark m : listed) {
                sb.append(m.number).append(". (str. ").append(m.page + 1).append(") ");
                if (!m.original.isEmpty() && !m.correction.isEmpty()) {
                    sb.append('„').append(m.original).append("“ → „").append(m.correction).append("“");
                } else if (!m.correction.isEmpty()) {
                    sb.append(m.correction);
                } else if (!m.original.isEmpty()) {
                    sb.append('„').append(m.original).append('“');
                }
                if (!m.explanation.isEmpty()) sb.append(" — ").append(m.explanation);
                if (!m.penalty.isEmpty()) sb.append(" (").append(m.penalty).append(')');
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    public String headline() {
        StringBuilder sb = new StringBuilder();
        if (!grade.isEmpty()) sb.append("Hodnocení: ").append(grade);
        if (!points.isEmpty() && !points.equals(grade)) {
            if (sb.length() > 0) sb.append("   ");
            sb.append("Body: ").append(points);
        }
        return sb.toString();
    }
}
