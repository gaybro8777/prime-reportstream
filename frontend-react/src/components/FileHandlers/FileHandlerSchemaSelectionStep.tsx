import { Button, FileInputRef, Select } from "@trussworks/react-uswds";
import { useRef } from "react";

import { FileHandlerStepProps } from "./FileHandler";
import FileHandlerPiiWarning from "./FileHandlerPiiWarning";
import useSenderSchemaOptions, {
    SchemaOption,
} from "../../senders/hooks/UseSenderSchemaOptions";
import Spinner from "../Spinner";

export interface FileHandlerSchemaSelectionStepProps
    extends FileHandlerStepProps {
    onSchemaChange: (schemaOption: SchemaOption) => void;
}

export default function FileHandlerSchemaSelectionStep({
    fileType,
    isValid,
    selectedSchemaOption,
    onSchemaChange,
    onNextStepClick,
}: FileHandlerSchemaSelectionStepProps) {
    const { data: schemaOptions, isLoading } = useSenderSchemaOptions();
    const fileInputRef = useRef<FileInputRef>(null);

    if (isLoading) {
        return (
            <div>
                <Spinner />

                <div className="text-center">Loading...</div>
            </div>
        );
    }

    return (
        <div>
            <FileHandlerPiiWarning />

            <p className="margin-top-4 margin-bottom-2">Select data model</p>

            <div className="margin-bottom-4">
                <Select
                    id="upload-schema-select"
                    name="upload-schema-select"
                    value={selectedSchemaOption.value}
                    onChange={(e) => {
                        const option = schemaOptions.find(
                            ({ value }: SchemaOption) =>
                                value === e.target.value,
                        )!;

                        if (option?.format !== fileType) {
                            fileInputRef.current?.clearFiles();
                        }

                        onSchemaChange(option);
                    }}
                >
                    <option value="" disabled>
                        - Select -
                    </option>
                    {schemaOptions.map(({ title, value }, index) => (
                        <option key={index} value={value}>
                            {title}
                        </option>
                    ))}
                </Select>
            </div>

            <Button
                disabled={!isValid}
                className="usa-button"
                type="submit"
                onClick={onNextStepClick}
            >
                Continue
            </Button>
        </div>
    );
}
