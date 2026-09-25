package cz.teacherfriend.redpen;

import android.app.Activity;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;

public final class SettingsActivity extends Activity {

    private Prefs prefs;
    private EditText apiKey;
    private Spinner model;
    private Spinner scale;
    private Spinner strictness;
    private EditText language;
    private CheckBox numbers;
    private CheckBox checks;
    private CheckBox summaryPage;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        prefs = new Prefs(this);

        apiKey = findViewById(R.id.api_key);
        model = findViewById(R.id.model);
        scale = findViewById(R.id.scale);
        strictness = findViewById(R.id.strictness);
        language = findViewById(R.id.language);
        numbers = findViewById(R.id.numbers);
        checks = findViewById(R.id.checks);
        summaryPage = findViewById(R.id.summary_page);

        setup(model, Prefs.MODEL_LABELS, Prefs.indexOf(Prefs.MODEL_IDS, prefs.model()));
        setup(scale, Prefs.SCALE_LABELS, Prefs.indexOf(Prefs.SCALE_IDS, prefs.scale()));
        setup(strictness, Prefs.STRICTNESS_LABELS, Prefs.indexOf(Prefs.STRICTNESS_IDS, prefs.strictness()));
        apiKey.setText(prefs.apiKey());
        language.setText(prefs.language());
        numbers.setChecked(prefs.numbers());
        checks.setChecked(prefs.checks());
        summaryPage.setChecked(prefs.summaryPage());
    }

    private void setup(Spinner s, String[] labels, int selected) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(selected);
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs.save(apiKey.getText().toString(),
                Prefs.MODEL_IDS[model.getSelectedItemPosition()],
                Prefs.SCALE_IDS[scale.getSelectedItemPosition()],
                Prefs.STRICTNESS_IDS[strictness.getSelectedItemPosition()],
                language.getText().toString(),
                numbers.isChecked(), checks.isChecked(), summaryPage.isChecked());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
