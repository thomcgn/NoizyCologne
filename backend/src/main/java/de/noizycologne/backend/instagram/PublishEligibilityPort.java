package de.noizycologne.backend.instagram;

/** Serverseitiger Gate-Port. Jeder echte Publisher muss ihn vor dem Senden aufrufen. */
public interface PublishEligibilityPort {
    PublishDecision evaluate(PublishContext context);
}
