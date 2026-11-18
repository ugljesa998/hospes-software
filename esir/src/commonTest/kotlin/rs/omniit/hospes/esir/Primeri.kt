package rs.omniit.hospes.esir

/**
 * Primeri odgovora za testove bez interneta.
 * Zasnovani na primeru „Example for E-SDC“ iz TaxCore dokumentacije (Get Status), bez stvarnih podataka.
 */
object Primeri {
    const val STATUS_LPFR = """
    {
      "isPinRequired": true,
      "auditRequired": false,
      "sdcDateTime": "2021-07-14T18:21:11.3320963+02:00",
      "lastInvoiceNumber": "CL4KBJCE-CL4KBJCE-1",
      "protocolVersion": "1.0.0.0",
      "hardwareVersion": "1.0.0.0",
      "softwareVersion": "1.0.0.0",
      "mssc": ["6001"],
      "gsc": ["1500"],
      "deviceSerialNumber": "99-3010-22222222",
      "make": "ExampleSDC",
      "model": "ExampleSDC",
      "supportedLanguages": ["en-US", "sr-Cyrl-RS"],
      "uid": "TK7SV2AY",
      "taxCoreApi": "https://api.sandbox.taxcore.dti.rs:443/",
      "nekoNovoPolje": "API sme da doda polja",
      "currentTaxRates": {
        "validFrom": "2020-08-03T12:56:56",
        "groupId": 2,
        "taxCategories": [
          {
            "name": "VAT",
            "categoryType": 0,
            "taxRates": [
              { "rate": 9, "label": "A" },
              { "rate": 0, "label": "B" }
            ],
            "orderId": 5
          },
          {
            "name": "PBL",
            "categoryType": 2,
            "taxRates": [ { "rate": 0.5, "label": "P" } ],
            "orderId": 3
          }
        ]
      },
      "allTaxRates": []
    }
    """
}
