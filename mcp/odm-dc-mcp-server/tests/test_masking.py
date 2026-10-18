"""PII masking: every output is masked, whatever the backend returned."""

from odm_dc_mcp.masking import MASK, luhn_ok, mask, mask_text

VALID_SIN = "046454286"  # the synthetic SIN of the contract examples: passes the Luhn check


def test_luhn_accepts_the_example_sin_and_rejects_a_random_number():
    assert luhn_ok(VALID_SIN)
    assert not luhn_ok("123456789")


def test_sin_is_masked_in_every_common_layout():
    for s in (VALID_SIN, "046 454 286", "046-454-286"):
        out = mask_text(f"applicant SIN {s} on file")
        assert "046" not in out and "<CA_SIN>" in out, out


def test_nine_digits_that_are_not_a_sin_are_left_alone():
    assert mask_text("reference 123456789 stays") == "reference 123456789 stays"


def test_postal_code_email_phone_and_card_are_masked():
    out = mask_text("M5V 2T6, a.tremblay@example.ca, (416) 555-0199, card 4111 1111 1111 1111")
    assert "M5V" not in out and "tremblay" not in out and "555-0199" not in out and "4111" not in out
    assert all(t in out for t in ("<CA_POSTAL_CODE>", "<EMAIL_ADDRESS>", "<PHONE_NUMBER>", "<CREDIT_CARD>")), out


def test_postal_code_without_a_space_and_in_lower_case():
    assert "<CA_POSTAL_CODE>" in mask_text("lives at m5v2t6 now")


def test_sensitive_keys_are_masked_by_name_whatever_the_value():
    out = mask(
        {
            "firstName": "Avery",
            "lastName": "Tremblay",
            "dateOfBirth": "1990-05-14",
            "sin": "x",
            "line1": "100 Queen St W",
            "postalCode": "whatever",
            "employer": "Example Corp",
            "nested": [{"given_name": "Sam", "ok": "fine"}],
        }
    )
    assert out["firstName"] == out["lastName"] == out["dateOfBirth"] == out["sin"] == out["line1"] == MASK
    assert out["postalCode"] == out["employer"] == MASK
    assert out["nested"][0] == {"given_name": MASK, "ok": "fine"}


def test_ids_numbers_and_rule_names_pass_untouched():
    data = {
        "applicationId": "APP-00000978",
        "name": "ELIG-01-AgeOfMajority",
        "creditLimit": 5000,
        "flag": True,
        "none": None,
        "eventId": "7b0f4c1e-5d52-4a53-9d2f-2f6f0d8e9a11",
    }
    assert mask(data) == data


def test_text_inside_a_rule_definition_is_scanned_too():
    out = mask({"definition": "if the SIN is 046-454-286 then escalate"})
    assert "046" not in out["definition"]
