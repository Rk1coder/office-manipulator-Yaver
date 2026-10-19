package com.officemanipulator.domain;

public enum OutputFormat {
    DOCX, PPTX, PDF, CSV, XLSX, ODT, ODS, ODP, ODB, DOC, TXT, MD, JSON, XML;

    public String fileExtension() {
        return name().toLowerCase();
    }

    /** The format Claude actually generates bytes in; {@link #ODT}/{@link #ODS}/{@link #ODP}/{@link #DOC}
     * are produced by generating this format first and converting via ONLYOFFICE's ConvertService. */
    public OutputFormat generationFormat() {
        return switch (this) {
            case ODT, DOC -> DOCX;
            case ODS -> XLSX;
            case ODP -> PPTX;
            default -> this;
        };
    }

    public String contentType() {
        return switch (this) {
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case PDF  -> "application/pdf";
            case CSV  -> "text/csv; charset=UTF-8";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ODT  -> "application/vnd.oasis.opendocument.text";
            case ODS  -> "application/vnd.oasis.opendocument.spreadsheet";
            case ODP  -> "application/vnd.oasis.opendocument.presentation";
            case ODB  -> "application/vnd.oasis.opendocument.database";
            case DOC  -> "application/msword";
            case TXT  -> "text/plain; charset=UTF-8";
            case MD   -> "text/markdown; charset=UTF-8";
            case JSON -> "application/json";
            case XML  -> "application/xml";
        };
    }
}
